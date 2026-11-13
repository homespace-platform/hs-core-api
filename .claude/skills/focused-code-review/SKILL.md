---
name: focused-code-review
description: Review a specified HomeSpace code diff, commit, pull request or working tree for correctness, security, compatibility and regressions. Use only when code review is explicitly requested.
---

# focused-code-review (Claude Code adapter for hs-core-api)

This is an adapter. It does not contain the workflow itself. The source workflow is the Codex skill at:

```text
../../../.agents/skills/focused-code-review/SKILL.md
```

(relative to this directory; it resolves to `.agents/skills/focused-code-review/SKILL.md` in the `hs-core-api` repository root).

Claude Code does not import that file automatically. When this skill is triggered:

1. Read the source file above with the Read tool.
2. Follow its workflow exactly, together with the `AGENTS.md` rules of this repository.
3. The source skill has no `references/` or `scripts/` directories. If they are added later, read them from the source skill directory when the workflow requires them.

The source directory also contains `agents/openai.yaml`. That file is Codex-only metadata; Claude Code ignores it.
