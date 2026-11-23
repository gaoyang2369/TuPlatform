# Project conventions

- TuPlatform connects requesters with teachers through discovery, applications/invitations, mutual selection, and consent-based contact sharing. Preserve both user journeys.
- Use `docs/mvp-spec.md` for business rules and `README.md` for setup. Update existing canonical docs when behavior changes; avoid duplicate handoff reports.
- Follow existing Vue 3 (`<script setup lang="ts">`), TypeScript, Vite, and Spring Boot/PostgreSQL patterns. The frontend entry is `frontend/src/AccountApp.vue`.
- Keep backend and business-logic changes focused and avoid unrelated refactors. Do not add non-UI dependencies without clear need (approved frontend UI stack is listed below).
- Keep user-facing copy and task summaries in Chinese unless requested otherwise.
- Enforce permissions and validation on the server. Preserve same-origin Cookie session, `/auth/csrf`, and `Idempotency-Key` behavior in `frontend/src/account-api.ts`, and preserve URL-parameter navigation behavior in `frontend/src/navigation.ts`.
- Never expose private contact details without valid mutual consent; preserve withdrawal and reconfirmation behavior. Never commit secrets or real personal data.

## Frontend Design System & UI/UX Standards

### Approved UI Stack
- Standardize new and refactored UI on **Tailwind CSS v4**, **shadcn-vue** (`frontend/src/components/ui/`), **@lucide/vue** (icons), and **Motion for Vue** (subtle layout/tab micro-interactions). Curated **Inspira UI** primitives may be used sparingly for polish.
- Keep shared tokens and base controls in `frontend/src/styles/theme.css`. Use Tailwind utilities and scoped component styles for active views.

### Visual Direction & Design Tokens (60-30-10)
- **Surfaces**: Page canvas `#F8FAFC`; elevated cards `#FFFFFF` with crisp `1px` `#E2E8F0` borders, `rounded-2xl` radius, and subtle hover elevation (`shadow-sm hover:shadow-lg hover:-translate-y-0.5 transition`).
- **Typography**: Primary ink `#0F172A`, secondary ink `#475569`, muted metadata `#64748B`. Body copy must be `>= 14px` (prefer `15px` for primary descriptions); tags/metadata `>= 12px`; key titles and hourly rates/budgets `>= 20px` bold for instant scanning.
- **Brand Accents**: Trust Cobalt `#2563EB` (hover `#1D4ED8`) for primary CTAs, active tabs, and verification badges; Neighborhood Amber/Orange `#EA580C` used sparingly (`<= 10%`) for urgent requests, trial-lesson cues, or highlight badges.

### Hard UI Anti-Patterns (Strictly Forbidden)
- Do **not** use dark forest-green buttons on warm beige/tan backgrounds.
- Do **not** constrain desktop discovery pages to a narrow single-column container (~760px) that wastes viewport width.
- Do **not** use unstyled native `<select>` dropdowns for primary discovery filters.
- Do **not** stack `1px` horizontal divider lines inside cards; establish hierarchy through spacing (`p-6`, `gap-4`), typographic scale, and soft surface contrast.
- Do **not** ship flat typography where names, rates, teaching directions, and metadata share similar size and weight.

### Layout, Component & Interaction Rules
- **Navigation**: Single-row sticky frosted-glass navbar (`backdrop-blur-md bg-white/80`) with a smooth active-tab indicator and clear requester/teacher identity display.
- **Discovery Grids**: Use `max-w-7xl mx-auto` containers with responsive multi-column grids (`grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6`) for teacher and public request listings.
- **Filters**: Use horizontal icon category pills plus instant-apply `Popover` filters (district multi-select, teaching mode, rate/budget range) rather than multi-step submit-button forms.
- **Card Hierarchy & Detail Drawer**: Structure cards for 2-second scanning (avatar or styled initial badge + verification cue, prominent bold rate/budget, concise teaching direction/need summary, district/mode pills, and a clear solid primary CTA). Prefer a right-side `shadcn-vue` `Sheet` drawer for quick profile/request inspection so users keep their list scroll position.
- **Progress & Communication**: Represent matching workflows (`MatchProgress.vue`) with a clear visual stepper rather than plain text status rows.
- **Motion & States**: Keep animations restrained and purposeful (tab indicator transitions, filter layout reflows, subtle card hover) and honor `prefers-reduced-motion`. Every changed view must include polished desktop and mobile layouts plus `Skeleton` loading, helpful empty, inline error, and success states. Verify the full affected requester/teacher flow, not just static appearance.

## Validation

- Frontend changes: run `npm.cmd test`, `npm.cmd run typecheck`, and `npm.cmd run build` in `frontend`.
- Backend changes: run `./backend/mvnw.ps1 test` from the repository root. Add focused regression coverage for changed business behavior.
- Run relevant browser/database tests when needed (including Playwright E2E checks when DOM structure or interactive flows change); follow `README.md` and use only the dedicated `_test` database with synthetic data. Report skipped checks accurately.
- Prepare migrations before applying them; obtain approval for new migrations on shared databases or server configuration changes. Reuse existing authorization where applicable.

## Delivery

- After implementation and relevant checks pass, review the diff, commit only this task's changes with a descriptive message, and push to the task branch's configured upstream without asking again.
- Preserve unrelated work and existing staged changes. Never force-push, discard others' changes, or include unrelated commits in a push. If the remote/branch is ambiguous, checks fail, or conflicts/authentication block delivery, report the blocker.
- Finish with a concise summary of changes, validation, commit hash, and push result. Do not claim deployment from a Git push alone.
