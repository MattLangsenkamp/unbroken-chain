# Sequence diagrams

Sequence diagrams show how systems interact and how data flows between them. They are a tool for explaining logic, not infrastructure.

## Rules

- **Label every step.** Each arrow gets a number, and each number gets a one-line explanation underneath the diagram.
- **Stay logical, not infrastructural.** Skip redundant low-level mechanics that don't change the meaning of the flow. For example, in a Kafka-based flow, don't draw consumer polling — show the message being delivered. We care about the flow of data and how systems interact, not the network protocol underneath.
- **Explain WHAT, not HOW.** The numbered list under the diagram should describe what is happening at each step. Implementation detail belongs in surrounding prose or in linked code paths, not in the step labels.
- **Include code paths.** When a step corresponds to specific code, link or reference it (`file/path.scala:123`) so the reader can jump from the diagram to the implementation.
- **Avoid branching inside a diagram.** `if` / `alt` blocks bloat the visual. Prefer a second diagram for the alternate path, repeating shared steps as needed. The only exception is when both branches are very small (one or two steps).

## Shape

```
<diagram>

1. Step one — what happens. (`path/to/file.scala:42`)
2. Step two — what happens.
3. ...
```

The diagram and the numbered list are a pair. Neither stands alone.
