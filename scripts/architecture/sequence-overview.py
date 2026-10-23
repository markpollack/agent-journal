#!/usr/bin/env python3
"""Compact, responsibility-level overview of one AppMap recording (scripts/architecture-docs.sh --appmap).

usage: sequence-overview.py <recording.appmap.json> <component-view.json> <out.puml>

Reads the recorded call tree of each thread and redraws it between the documentation components of component-view.json (the
same membership the extracted view and ArchitectureBoundaryTest use), so the overview and the detailed trace show one recording
at two zoom levels. Every rule is deterministic and stated here:

  fold    a call to the same class and method as its caller (overload delegation), and a call within its caller's component
          unless the caller is the thread's first recorded call (whose own steps stay visible as self-messages); the folded
          call's children take its place. A call into a shared component (component-view.json's incoming_edges_not_drawn_to,
          as in the component view) is drawn on its caller's lifeline
  repeat  a block of consecutive sibling calls repeated with the same shape (component, class, method and children, with
          repetitions inside them ignored, so one read chunk or two give the same shape) becomes one loop; the loop shows the
          repetition with the most recorded calls, and each loop's count is how often its first call was recorded in all
          repetitions of the loops around it
  par     a second thread is drawn as the other branch of a par block around the main-thread loop whose calls have the same
          receiver object as that thread's first call (for CliCapture: the awaitEnd polls on the Pump whose run() is the reader
          thread). The par states that the branches ran on two threads at once, nothing about the order between them; AppMap
          event ids do not give one (an id can be assigned on both threads). Without such a loop the thread is drawn after the
          main thread, marked unordered
  labels  participants carry a short name (the legend gives each one's component) in component-view.json's order; the
          unrecorded test's calls arrive from the left edge; messages name the simple class and method; return values are
          omitted

Each message is preceded by a comment naming its thread and the recorded call ids it stands for (and those folded into it);
each loop lists every recorded call it covers. These comments are the route back to the raw events (an id is unique only
together with its thread).
"""
import json
import sys
import textwrap

WRITER = "io.github.markpollack.journal.ocsf.writer."
TCK = "io.github.markpollack.journal.tck."

# Presentation labels for loops, keyed by the first call of the repeated body (component id, class, method).
LOOP_LABELS = {
    ("capture", "CliCapture$Pump", "awaitEnd"): "until end of input",
    ("run-writer", "OcsfRunWriter$Source", "bytes"): "each chunk read",
    ("compatibility", "Compatibility", "bootstrap"): "each native record",
    ("reader", "PackageReader", "open"): "each opening",
}


def short(fq):
    for root in (WRITER, TCK):
        if fq.startswith(root):
            return fq[len(root):]
    return fq


class Call:
    def __init__(self, event):
        self.ids = [event["id"]]
        self.folded = []
        self.cls = short(event["defined_class"])
        self.method = event["method_id"]
        self.children = []
        self.thread = event["thread_id"]
        self.receiver = (event.get("receiver") or {}).get("object_id")

    @property
    def simple(self):
        return self.cls.split(".")[-1]

    @property
    def top(self):
        return self.cls.split("$")[0]

    def label(self):
        return self.simple.replace("$", ".") + "." + self.method

    def all_ids(self):
        out = self.ids + self.folded
        for c in self.children:
            out.extend(c.all_ids())
        return out


class Loop:
    """A repeated block: every recorded repetition, in order."""

    def __init__(self, repetitions):
        self.repetitions = repetitions

    def all_ids(self):
        return [i for block in self.repetitions for item in block for i in item.all_ids()]

    def body(self):
        return max(self.repetitions, key=lambda block: sum(len(item.all_ids()) for item in block))


def trees(recording):
    roots, stacks = {}, {}
    for e in recording["events"]:
        stack = stacks.setdefault(e["thread_id"], [])
        if e["event"] == "call":
            call = Call(e)
            (stack[-1].children if stack else roots.setdefault(e["thread_id"], [])).append(call)
            stack.append(call)
        elif e["event"] == "return":
            done = stack.pop()
            if done.ids[0] != e["parent_id"]:
                raise SystemExit(f"return {e['id']} does not close call {done.ids[0]}")
    return roots


def fold(call, component, root):
    kept = []
    for child in call.children:
        child_kept = fold(child, component, False)
        same_method = child.top == call.top and child.method == call.method
        same_component = component(child) == component(call)
        if same_method or (same_component and not root):
            call.folded.extend(child.ids + child.folded)
            kept.extend(child_kept)
        else:
            child.children = child_kept
            kept.append(child)
    return kept


def shapes(items, component):
    """The canonical shape of a list of calls: loops flattened to one body, then repeated blocks reduced to one copy."""
    seq = []
    for item in items:
        if isinstance(item, Loop):
            seq.extend(shapes(item.body(), component))
        else:
            seq.append((component(item), item.cls, item.method, shapes(item.children, component)))
    changed = True
    while changed:
        changed = False
        for k in range(1, len(seq) // 2 + 1):
            for i in range(len(seq) - 2 * k + 1):
                if seq[i:i + k] == seq[i + k:i + 2 * k]:
                    seq = seq[:i + k] + seq[i + 2 * k:]
                    changed = True
                    break
            if changed:
                break
    return tuple(seq)


def repeat(items, component):
    for item in items:
        if isinstance(item, Call):
            item.children = repeat(item.children, component)
    out, i = [], 0
    while i < len(items):
        best_k, best_r = 1, 1
        for k in range(1, (len(items) - i) // 2 + 1):
            block = shapes(items[i:i + k], component)
            r = 1
            while i + (r + 1) * k <= len(items) and shapes(items[i + r * k:i + (r + 1) * k], component) == block:
                r += 1
            if r > 1 and r * k > best_r * best_k:
                best_k, best_r = k, r
        if best_r > 1:
            out.append(Loop([items[i + r * best_k:i + (r + 1) * best_k] for r in range(best_r)]))
            i += best_r * best_k
        else:
            out.append(items[i])
            i += 1
    return out


def first_call(items):
    item = items[0]
    return first_call(item.body()) if isinstance(item, Loop) else item


def occurrences(items, key, component):
    """How often a call of this (component, class, method) was recorded among these items, nested loops included."""
    n = 0
    for item in items:
        if isinstance(item, Loop):
            n += sum(occurrences(block, key, component) for block in item.repetitions)
        else:
            n += ((component(item), item.cls, item.method) == key) + occurrences(item.children, key, component)
    return n


def main(recording_path, view_path, out_path):
    recording = json.load(open(recording_path, encoding="utf-8"))
    view = json.load(open(view_path, encoding="utf-8"))
    member = {cls: c["id"] for c in view["components"] for cls in c["classes"]}
    shared = set(view["drawing"]["incoming_edges_not_drawn_to"])
    order = [c["id"] for c in view["components"]]
    names = {c["id"]: c["name"] for c in view["components"]}

    def component(call):
        if call.top not in member:
            raise SystemExit(f"{call.cls} is not in any component of {view_path}")
        return member[call.top]

    roots = trees(recording)
    main_thread = recording["events"][0]["thread_id"]
    for thread_roots in roots.values():
        for r in thread_roots:
            r.children = fold(r, component, True)
    for thread in roots:
        roots[thread] = repeat(roots[thread], component)

    # place every other thread beside the main-thread loop of calls on its first call's receiver
    beside, unordered = {}, []
    for thread, thread_roots in roots.items():
        if thread == main_thread:
            continue
        host = find_loop(roots[main_thread], thread_roots[0].receiver)
        if host is None:
            unordered.append(thread_roots)
        else:
            beside[id(host)] = thread_roots

    used, lines = [], []

    def use(cid):
        if cid not in used:
            used.append(cid)

    def emit(item, caller, depth, scope):
        """scope: the repetitions of the enclosing loops, to count a nested loop over all of them."""
        if isinstance(item, Loop):
            head = first_call(item.body())
            key = (component(head), head.cls, head.method)
            total = sum(occurrences(block, key, component) for block in scope) if scope else occurrences([item], key, component)
            label = LOOP_LABELS.get((key[0], head.cls.split(".")[-1], head.method), "repeated")
            lines.append("  " * depth + f"loop {label}, {total} recorded")
            lines.append("  " * depth + "' covers recorded calls " + ", ".join(str(i) for i in sorted(item.all_ids())))
            for child in item.body():
                emit_with_threads(child, caller, depth + 1, [[x] for block in item.repetitions for x in block] if not scope else scope)
            lines.append("  " * depth + "end")
            return
        callee = component(item)
        if callee in shared:
            callee = caller
        use(callee)
        note = f"' thread {item.thread}, recorded call " + ", ".join(str(i) for i in item.ids)
        if item.folded:
            note += "; folded into it " + ", ".join(str(i) for i in sorted(item.folded))
        lines.append("  " * depth + note)
        arrow = "[->" if caller == "test" else f"{alias(caller)} ->"
        lines.append("  " * depth + f"{arrow} {alias(callee)} : {item.label()}")
        for child in item.children:
            emit_with_threads(child, callee, depth, scope)

    def emit_with_threads(item, caller, depth, scope):
        other = beside.get(id(item))
        if other is None:
            emit(item, caller, depth, scope)
            return
        lines.append("  " * depth + f"par {thread_label(item)}")
        emit(item, caller, depth + 1, scope)
        for r in other:
            lines.append("  " * depth + f"else stdout reader thread: {r.label()}")
            for child in r.children:
                emit_with_threads(child, component(r), depth + 1, None)
        lines.append("  " * depth + "end")

    for r in roots[main_thread]:
        emit_with_threads(r, "test", 0, None)
    for thread_roots in unordered:
        lines.append("group another thread, order relative to the main thread not established")
        for r in thread_roots:
            emit(r, "test", 1, None)
        lines.append("end")

    with open(out_path, "w", encoding="utf-8") as out:
        out.write("@startuml observed-launch-overview\n")
        out.write("' GENERATED by scripts/architecture/sequence-overview.py from the AppMap recording named in observed-launch-recording.json\n")
        out.write("' and the component membership of component-view.json; do not edit. The rules are in the generator's header.\n")
        out.write("skinparam shadowing false\nskinparam defaultFontName Helvetica\nskinparam defaultFontSize 12\nskinparam maxMessageSize 150\nhide footbox\n")
        out.write("title Observed (overview): capture, then independent check, in one replayed launching-capture test\n")
        used.sort(key=order.index)
        for cid in used:
            out.write(f'participant "{cid.replace("-", " ")}" as {alias(cid)}\n')
        out.write("\n".join(lines) + "\n")
        legend = ["Participants are documentation components (component-view.md): "
                  + "; ".join(f"{cid.replace('-', ' ')}: {names[cid]}" for cid in used) + ". "
                  + "Arrows from the left edge are the test's own calls (not recorded). Calls into the shared JSON and byte code are "
                  + "drawn on their caller.",
                  "One replayed test: a stand-in script replays a labelled synthetic Codex exec stream through CliCapture; not a live run. "
                  + "Calls inside a component are folded, except a thread's own steps.",
                  "par: the two branches ran at once on two threads (the polls and the reader's run() are calls on one Pump object; "
                  + "the polls returned false until the reader ended). Position orders calls within a branch only. "
                  + "Full trace: observed-launch-sequence.svg."]
        out.write("legend bottom left\n")
        for paragraph in legend:
            for line in textwrap.wrap(paragraph, 96):
                out.write("  " + line + "\n")
        out.write("endlegend\n@enduml\n")


def find_loop(items, receiver):
    for item in items:
        if isinstance(item, Loop) and receiver is not None and first_call(item.body()).receiver == receiver:
            return item
        children = item.body() if isinstance(item, Loop) else item.children
        found = find_loop(children, receiver)
        if found is not None:
            return found
    return None


def thread_label(loop):
    return "main thread"


def alias(cid):
    return cid.replace("-", "_")




if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    main(*sys.argv[1:])
