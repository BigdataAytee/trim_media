# Claude Code configuration for Trim

## Plugin marketplace

`settings.json` registers <https://github.com/anthropics/skills> as the
`anthropic-agent-skills` marketplace and enables three of its plugins:

| Plugin | Skills it brings | Why it is on for this repo |
|---|---|---|
| `example-skills` | `skill-creator`, `mcp-builder`, `webapp-testing`, `frontend-design`, `brand-guidelines`, `canvas-design`, `doc-coauthoring`, `internal-comms`, `algorithmic-art`, `slack-gif-creator`, `theme-factory`, `web-artifacts-builder` | `frontend-design` / `canvas-design` for Milestone 5 (`core/ui` design system, screen mockups); `skill-creator` for packaging Trim-specific repo skills |
| `document-skills` | `xlsx`, `docx`, `pptx`, `pdf` | Milestone 4's calibration harness emits CSV/XLSX ladders of (clip, setting, xpsnr, vmaf) |
| `claude-api` | `claude-api` | reference when any tooling around the repo talks to the Claude API |

Nothing here is a build dependency — the Gradle build has no knowledge of
plugins, and `./gradlew check` runs identically without them.

### Verifying / refreshing

```
/plugin marketplace add anthropics/skills     # first-time trust prompt
/plugin marketplace update anthropic-agent-skills
/plugin                                        # browse what is enabled
```

Claude Code fetches the marketplace on first use; the clone lives outside the
repo, so nothing is vendored here.
