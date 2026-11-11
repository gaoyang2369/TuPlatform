# Project conventions

- TuPlatform connects requesters with teachers through discovery, applications/invitations, mutual selection, and consent-based contact sharing. Preserve both user journeys.
- Use `docs/mvp-spec.md` for business rules and `README.md` for setup. Update existing canonical docs when behavior changes; avoid duplicate handoff reports.
- Follow existing Vue 3/TypeScript and Spring Boot/PostgreSQL patterns. The frontend entry is `frontend/src/AccountApp.vue`; do not extend the legacy prototype accidentally.
- Keep changes focused, reuse existing components, and avoid unrelated refactors or new dependencies. Keep user-facing copy and task summaries in Chinese unless requested otherwise.
- Enforce permissions and validation on the server. Never expose private contact details without valid mutual consent; preserve withdrawal and reconfirmation behavior. Never commit secrets or real personal data.
- Check changed UI at desktop and mobile widths, including loading, empty, error, and success states. Verify the affected requester/teacher flow, not just appearance.

## Validation

- Frontend changes: run `npm.cmd test`, `npm.cmd run typecheck`, and `npm.cmd run build` in `frontend`.
- Backend changes: run `./backend/mvnw.ps1 test` from the repository root. Add focused regression coverage for changed business behavior.
- Run relevant browser/database tests when needed; follow `README.md` and use only the dedicated `_test` database with synthetic data. Report skipped checks accurately.
- Prepare migrations before applying them; obtain approval for new migrations on shared databases or server configuration changes. Reuse existing authorization where applicable.

## Delivery

- After implementation and relevant checks pass, review the diff, commit only this task's changes with a descriptive message, and push to the task branch's configured upstream without asking again.
- Preserve unrelated work and existing staged changes. Never force-push, discard others' changes, or include unrelated commits in a push. If the remote/branch is ambiguous, checks fail, or conflicts/authentication block delivery, report the blocker.
- Finish with a concise summary of changes, validation, commit hash, and push result. Do not claim deployment from a Git push alone.
