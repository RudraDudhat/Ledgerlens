# CLAUDE.md — ledgerlens

ledgerlens: an AI Finance Controller agent for Indian merchants using Razorpay. It reconciles three inputs — the merchant's order export, the Razorpay settlement report, and the bank statement — then explains every rupee of difference between "sales" and "money received in bank" as a waterfall, lists unmatched records as exceptions with reasons and confidence, forecasts upcoming settlements, and answers plain-English questions grounded in the reconciled rows.

Work proceeds in user-gated phases: at the end of each phase, run the tests, print "✅ Phase N complete" with files changed and test pass/fail counts, print the COMMIT PLAN (format in `<git_rules>`), then STOP and wait for the user to commit and reply "go". Correctness and honest metrics matter more than feature count. Only build what is specified; do not add extra files, abstractions, features, or refactors beyond what is asked.

The four sections below are copied verbatim from the project brief and are binding in every session.

<stack>
- Backend: Java 21, Spring Boot 3.x (spring-boot-starter-web, spring-boot-starter-data-jpa, spring-boot-starter-validation), Maven, groupId com.ledgerlens
- DB: PostgreSQL 16. NO Flyway, NO Liquibase. Schema is managed by a hand-written src/main/resources/schema.sql applied via spring.sql.init.mode=always with spring.jpa.hibernate.ddl-auto=validate. All money columns MUST be NUMERIC(14,2) mapped to java.math.BigDecimal. Never use double/float for money.
- AI: Spring AI with the Anthropic starter, structured output mapped to Java records. Model name read from application.yml. API key read ONLY from env var ANTHROPIC_API_KEY — never hardcode, never commit.
- CSV: Apache Commons CSV
- Razorpay: com.razorpay:razorpay-java SDK for test-mode fetch of payments, refunds, settlements (keys from env vars RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET). CSV ingestion must work fully without Razorpay keys present.
- Tests: JUnit 5, Testcontainers (postgres), AssertJ
- Frontend: React 18 + Vite + TypeScript, Tailwind, TanStack Table, Recharts
- Runtime: docker-compose.yml with services postgres, backend, frontend
</stack>

<domain_rules>
Model these exactly; they drive the waterfall.
- Payment methods: UPI, CARD, NETBANKING, WALLET.
- Fees: UPI 0%. CARD 2.0%. NETBANKING 1.9%. WALLET 2.0%. Fees are rounded to 2 decimals per payment.
- GST: 18% on the fee amount (not on gross). Rounded to 2 decimals per payment.
- Settlement cycle: UPI and WALLET settle T+1 business day; CARD and NETBANKING settle T+2 business days. Saturday/Sunday are not business days (ignore public holidays).
- Refunds are deducted from the settlement cycle in which the refund was CREATED, which may be a later cycle than the original payment.
- A payment with an open dispute is HELD: excluded from settlement until dispute is resolved. Expected release = dispute.opened_at + 14 days if status is WON.
- A settlement batch = sum(net of all payments settling that day) - sum(refunds created that day) and lands in the bank as ONE credit with a UTR.
- Status values for exceptions: MATCHED, PAYMENT_FAILED, HELD_DISPUTE, REFUND_PRIOR_CYCLE, BANK_DUPLICATE, BANK_MISSING, AMOUNT_MISMATCH, UNKNOWN.
</domain_rules>

<git_rules>
I commit manually. You NEVER run git add, git commit, git branch, git checkout, git merge, git tag, git push, or any other git command that changes state. You MAY run git status, git diff, and git log to inspect. The only exception: in Phase 0 you MAY run `git init` once.

Your job at the end of every phase is to produce a COMMIT PLAN in this exact format so I can run it:

=== COMMIT PLAN — Phase N ===
Branch: feat/<scope>            (create from main: git checkout -b feat/<scope>)

Commit 1: <type>(<scope>): <imperative summary>
  files:
    <path>
    <path>
  body (optional, why not how):
    <one or two lines>

Commit 2: ...

After all commits:
  git checkout main && git merge --no-ff feat/<scope> && git branch -d feat/<scope>
  [git tag <tag>   — only if this phase is a tagged milestone]
=== END ===

Rules for the plan:
- Conventional Commits only: type(scope): imperative summary. Types: feat, fix, test, refactor, docs, chore. Scopes: db, generator, ingest, matcher, rules, forecast, ai, api, frontend, docker.
- One logical change per commit. If the summary needs "and", split it. Aim for 2–5 commits per phase.
- Docs changes (README, CLAUDE.md) are always their own commit.
- Every commit's file set MUST compile on its own. Before producing the plan, run `mvn -q compile` (and `npm run build` if frontend changed) and confirm they pass.
- Never include secrets. Provide backend/src/main/resources/application-example.yml with placeholders; real application.yml is gitignored.
- Tagged milestones: after Phase 3 → v0.1-matcher, after Phase 6 → v0.2-rules, after Phase 9 → v1.0-submission.
- Phase 0's plan is a single commit on main (no branch): chore: initial project skeleton
</git_rules>

<api_contract>
POST /api/ingest/csv           multipart: orders, settlement, bank → returns batch id
POST /api/ingest/razorpay      fetch test-mode data for a date range → batch id
POST /api/reconcile/{batchId}  runs matcher + rules → returns summary
GET  /api/reconcile/{batchId}/summary     match rate, counts by status, totals
GET  /api/reconcile/{batchId}/waterfall   ordered list of {label, amount, sourceRowIds[]}
GET  /api/reconcile/{batchId}/exceptions  list with status, reason, confidence, sourceRowIds[]
GET  /api/reconcile/{batchId}/matches     paginated matched rows
GET  /api/forecast/{batchId}              list of {date, expectedAmount, breakdownByMethod, heldAmount}
POST /api/ask/{batchId}                   {question} → {answer, citedRowIds[]}
GET  /api/metrics/{batchId}               precision/recall per exception type vs answer_key.json if present, plus calibration buckets
Every reconcile run writes to an append-only audit_log table (a trigger in schema.sql blocks UPDATE and DELETE on it).
</api_contract>
