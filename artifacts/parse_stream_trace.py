#!/usr/bin/env python3
"""Summarize an Android ART streaming method trace (SLOW version 3)."""

from __future__ import annotations

import collections
import struct
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass
class Event:
    thread: int
    method: int
    action: int
    thread_us: int
    wall_us: int


def u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def read_trace(path: Path):
    data = path.read_bytes()
    if len(data) < 32 or data[:4] != b"SLOW":
        raise ValueError("not an ART SLOW method trace")

    raw_version = u16(data, 4)
    streaming = (raw_version & 0xF0) == 0xF0
    version = raw_version ^ 0xF0 if streaming else raw_version
    offset = u16(data, 6)
    start_us = struct.unpack_from("<Q", data, 8)[0]
    record_size = u16(data, 16) if version >= 3 else (10 if version == 2 else 9)
    if not streaming or version != 3:
        raise ValueError(f"expected streaming version 3, got 0x{raw_version:x}")

    methods: dict[int, str] = {}
    threads: dict[int, str] = {}
    events: list[Event] = []
    cursor = offset
    truncated = False

    while cursor < len(data):
        if cursor + 2 > len(data):
            truncated = True
            break
        thread = u16(data, cursor)
        if thread == 0:
            if cursor + 3 > len(data):
                truncated = True
                break
            code = data[cursor + 2]
            if code == 1:
                if cursor + 5 > len(data):
                    truncated = True
                    break
                length = u16(data, cursor + 3)
                end = cursor + 5 + length
                if end > len(data):
                    truncated = True
                    break
                line = data[cursor + 5 : end].decode("utf-8", errors="replace").rstrip("\n")
                fields = line.split("\t")
                try:
                    method_id = int(fields[0], 0)
                except (ValueError, IndexError):
                    method_id = -1
                if method_id >= 0:
                    methods[method_id] = "\t".join(fields[1:])
                cursor = end
            elif code == 2:
                if cursor + 7 > len(data):
                    truncated = True
                    break
                trace_thread = u16(data, cursor + 3)
                length = u16(data, cursor + 5)
                end = cursor + 7 + length
                if end > len(data):
                    truncated = True
                    break
                threads[trace_thread] = data[cursor + 7 : end].decode("utf-8", errors="replace")
                cursor = end
            else:
                raise ValueError(f"unknown streaming special record {code} at {cursor}")
            continue

        if cursor + record_size > len(data):
            truncated = True
            break
        encoded = u32(data, cursor + 2)
        events.append(
            Event(
                thread=thread,
                method=encoded & ~0x3,
                action=encoded & 0x3,
                thread_us=u32(data, cursor + 6),
                wall_us=u32(data, cursor + 10),
            )
        )
        cursor += record_size

    return {
        "data_size": len(data),
        "version": version,
        "offset": offset,
        "start_us": start_us,
        "record_size": record_size,
        "methods": methods,
        "threads": threads,
        "events": events,
        "truncated": truncated,
    }


def display_method(methods: dict[int, str], method_id: int) -> str:
    raw = methods.get(method_id, f"0x{method_id:x}\t<unknown>")
    fields = raw.split("\t")
    if len(fields) >= 3:
        class_name, method_name, signature = fields[:3]
        return f"{class_name}.{method_name}{signature}"
    return raw


def summarize(trace) -> None:
    methods = trace["methods"]
    threads = trace["threads"]
    events: list[Event] = trace["events"]

    print(
        f"size={trace['data_size']} version={trace['version']} offset={trace['offset']} "
        f"record_size={trace['record_size']} methods={len(methods)} threads={len(threads)} "
        f"events={len(events)} truncated={trace['truncated']}"
    )
    for thread_id, name in sorted(threads.items()):
        count = sum(1 for event in events if event.thread == thread_id)
        if name == "main" or count >= 1000:
            print(f"thread {thread_id}: {name!r}, events={count}")

    main_ids = [thread_id for thread_id, name in threads.items() if name == "main"]
    main_id = main_ids[0] if main_ids else 1
    main_events = [event for event in events if event.thread == main_id]
    if not main_events:
        raise ValueError(f"no events for main trace thread {main_id}")

    # Apply every same-timestamp stack transition, then charge the resulting
    # sampled stack until the next timestamp.
    grouped: dict[int, list[Event]] = collections.OrderedDict()
    for event in main_events:
        grouped.setdefault(event.wall_us, []).append(event)

    stack: list[int] = []
    inclusive_us: collections.Counter[int] = collections.Counter()
    exclusive_us: collections.Counter[int] = collections.Counter()
    samples: collections.Counter[int] = collections.Counter()
    stacks: collections.Counter[tuple[int, ...]] = collections.Counter()
    mismatches = 0
    previous_time = None
    previous_stack: tuple[int, ...] = ()

    for wall_us, same_time_events in grouped.items():
        if previous_time is not None:
            duration = (wall_us - previous_time) & 0xFFFFFFFF
            if duration < 5_000_000:  # Ignore impossible wrap/corrupt gaps.
                for method_id in previous_stack:
                    inclusive_us[method_id] += duration
                if previous_stack:
                    exclusive_us[previous_stack[-1]] += duration
                    samples[previous_stack[-1]] += 1
                    stacks[previous_stack] += duration

        for event in same_time_events:
            if event.action == 0:
                stack.append(event.method)
            elif event.action in (1, 2):
                if stack and stack[-1] == event.method:
                    stack.pop()
                elif event.method in stack:
                    mismatches += 1
                    reverse_index = stack[::-1].index(event.method)
                    del stack[len(stack) - 1 - reverse_index :]
                else:
                    mismatches += 1
            else:
                mismatches += 1

        previous_time = wall_us
        previous_stack = tuple(stack)

    first_wall = min(grouped)
    last_wall = max(grouped)
    print(
        f"main_thread={main_id} samples={len(grouped)} first_wall_us={first_wall} "
        f"last_wall_us={last_wall} span_s={(last_wall-first_wall)/1_000_000:.3f} "
        f"stack_mismatches={mismatches} final_depth={len(stack)}"
    )

    print("\nTOP MAIN-THREAD INCLUSIVE")
    for method_id, duration in inclusive_us.most_common(40):
        print(f"{duration/1_000_000:9.3f}s  {display_method(methods, method_id)}")

    print("\nTOP MAIN-THREAD EXCLUSIVE")
    for method_id, duration in exclusive_us.most_common(50):
        print(
            f"{duration/1_000_000:9.3f}s  samples={samples[method_id]:6d}  "
            f"{display_method(methods, method_id)}"
        )

    print("\nTOP MAIN-THREAD STACKS")
    for sampled_stack, duration in stacks.most_common(15):
        print(f"--- {duration/1_000_000:.3f}s ---")
        for method_id in sampled_stack[-18:]:
            print(f"  {display_method(methods, method_id)}")

    print("\nFINAL MAIN-THREAD STACK")
    for method_id in stack:
        print(f"  {display_method(methods, method_id)}")


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} TRACE", file=sys.stderr)
        return 2
    summarize(read_trace(Path(sys.argv[1])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
