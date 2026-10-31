### OpenCode — Product Strategy & Roadmap

### Building the Most Reliable Open-Source AI Software Engineering Environment

Core Vision: Every Change Has Evidence.

Transform OpenCode from an AI coding assistant into a reliable, local-first software engineering environment designed for professional developers, complex codebases, and large-scale software projects.

The goal is not simply to generate better code. The goal is to make AI-driven software development safer, more predictable, verifiable, and recoverable.

Strategic constraint: The entire core product must remain open-source, local-first, and self-hostable. No mandatory cloud infrastructure, paid APIs, subscriptions, hosted services, or proprietary backend dependencies.

### 1\. Product Positioning

OpenCode should become the preferred AI engineering environment for developers who care about code quality, reliability, security, and control.

### Target Audience

* Senior and professional software engineers.

* Developers working with large and complex codebases.

* Engineers maintaining legacy applications.

* Developers performing large-scale refactoring and migrations.

* Security-conscious developers.

* Teams that require local execution and control over their source code.

### Core Product Principles

* Reliability over autonomy.

* Verified results over generated code.

* Local execution over cloud dependency.

* Human control over unrestricted automation.

* Reproducibility over unpredictable AI behavior.

* Open-source extensibility over vendor lock-in.

### Main Competitive Advantage

OpenCode should compete on the cost of producing a correct, verified, and recoverable software change.

The central promise:

OpenCode doesn't just write code. It proves that the changes are correct.

### 2\. Core Architecture — Reliable Engineering Core

Build on the existing OpenCode architecture instead of rewriting everything from scratch.

Introduce a reliability-focused architecture with the following components:

### A. Task Orchestrator

A central system responsible for understanding, planning, executing, and tracking software engineering tasks.

Capabilities:

* Break complex requests into structured tasks.

* Define acceptance criteria before implementation.

* Identify dependencies between tasks.

* Track execution states.

* Detect incomplete or failed operations.

* Prevent conflicting changes.

* Support task cancellation and recovery.

* Maintain an execution history.

The orchestrator must use an explicit state machine rather than relying entirely on conversational context.

### B. Execution Engine

A secure local execution environment.

Capabilities:

* Execute tasks in isolated Git worktrees.

* Preserve existing user modifications.

* Run terminal commands.

* Execute project-specific scripts.

* Manage temporary branches.

* Detect file conflicts.

* Support rollback and recovery.

* Enforce filesystem and network permissions.

* Apply execution time and resource limits.

All execution must happen locally by default.

### C. Verification Engine

An independent validation system responsible for checking the actual results of AI-generated changes.

Capabilities:

* Run unit tests.

* Run integration tests.

* Run end-to-end tests when available.

* Run linting.

* Run type checking.

* Detect regressions.

* Validate API compatibility.

* Review modified files.

* Compare implementation against acceptance criteria.

* Identify missing test coverage.

Important rule:

An AI agent must never be allowed to declare its own work successfully verified without independent evidence.

### D. Evidence Ledger

A structured record of everything that happened during a task.

For every task, store:

* Original Git commit.

* Initial working tree state.

* Modified files.

* Generated patches.

* Commands executed.

* Test results.

* Error messages.

* Verification results.

* Agent decisions.

* Remaining risks.

* Recovery information.

The evidence ledger must be stored locally and use a documented, portable format.

### E. Project Intelligence Engine

A persistent understanding of the codebase.

Capabilities:

* Incremental code indexing.

* Dependency analysis.

* Module relationship mapping.

* API contract discovery.

* Architecture discovery.

* Existing coding convention detection.

* Test discovery.

* Symbol and reference tracking.

* Identification of critical modules.

* Tracking of architectural decisions.

The index should update incrementally when files change rather than rebuilding the entire project.

### F. Model Adapter Layer

Maintain compatibility with different AI providers and local models.

Capabilities:

* Unified model interface.

* Model capability detection.

* Context-window awareness.

* Structured output support.

* Tool-calling compatibility.

* Retry and fallback handling.

* Model-specific configuration.

* Local model support.

Model selection should remain under developer control.

No mandatory paid model provider should be required.

### G. Policy Engine

A security layer independent of AI agents.

Capabilities:

* Fine-grained permissions.

* Filesystem access restrictions.

* Command execution policies.

* Network access controls.

* Sensitive file protection.

* Explicit approval for dangerous operations.

* Audit logs.

* Protection against prompt injection from repository files and terminal output.

The AI model must never be able to grant itself additional permissions.

### 3\. Priority Features

### P0 — Essential Features

These features should be implemented first.

### 1\. Mission Control

A central interface for managing complex engineering tasks.

Capabilities:

* Automatically break large tasks into smaller tasks.

* Assign responsibilities to specialized agents.

* Track task dependencies.

* Monitor execution status.

* Detect blocked tasks.

* Display progress.

* Manage task cancellation.

* Provide a complete execution summary.

Agents must work within clearly defined file boundaries and task responsibilities.

Parallel execution should only happen when tasks are sufficiently independent.

### 2\. Proof of Work

Every completed task must include verifiable evidence.

The final report should contain:

* What was changed.

* Why it was changed.

* Which files were modified.

* Which tests were executed.

* Which tests passed.

* Which tests failed.

* Which tests were not executed.

* Remaining risks.

* Whether the change is fully verified.

Use explicit task states:

* Planned

* In Progress

* Implemented

* Verification Pending

* Verified

* Failed

* Blocked

Never display an unverified implementation as successfully completed.

### 3\. Safe Git Workspaces

Introduce reliable task isolation using Git worktrees.

Capabilities:

* Create isolated workspaces for tasks.

* Preserve user modifications.

* Maintain independent task branches.

* Compare changes against the original state.

* Detect conflicting modifications.

* Support selective rollback.

* Recover interrupted tasks.

* Merge changes only after review.

The system must never silently discard existing developer changes.

### 4\. Intelligent Model Routing

Choose the most appropriate available model for each task based on:

* Task complexity.

* Model capabilities.

* Context requirements.

* Historical task performance.

* Local availability.

* Latency.

* Estimated cost.

Examples:

* Simple code search: fast model.

* Complex architecture analysis: stronger reasoning model.

* Independent code review: different model when available.

* Routine documentation: efficient local model.

Allow developers to disable automatic routing and select models manually.

### 5\. Trust & Permission Layer

Security must be a core architectural feature.

Implement:

* Sandboxed execution.

* Restricted filesystem access.

* Command approval policies.

* Network restrictions.

* Secret protection.

* Dangerous command detection.

* Explicit approval for destructive operations.

* Execution audit logs.

* Prompt injection resistance.

All security decisions must be enforced outside the AI model.

### 4\. P1 — Advanced Engineering Capabilities

### 6\. Project Brain

Create a persistent, structured understanding of every project.

The system should understand:

* Project architecture.

* Module dependencies.

* Important business logic.

* API contracts.

* Database schemas.

* Coding conventions.

* Architectural decisions.

* Critical tests.

* Known technical debt.

Memory must be grounded in actual source files and documentation.

Every important architectural claim should be traceable to its source.

### 7\. Change Impact Simulator

Before implementing a change, analyze its potential impact.

Capabilities:

* Identify affected modules.

* Discover dependent functions and classes.

* Detect API compatibility risks.

* Identify relevant tests.

* Highlight critical execution paths.

* Estimate the scope of modifications.

* Explain why particular files need to change.

The system should generate an impact report before modifying important parts of the codebase.

### 8\. Adversarial Code Review

Introduce an independent code reviewer.

The reviewer should analyze:

* Logic errors.

* Edge cases.

* Security vulnerabilities.

* Performance regressions.

* API compatibility.

* Missing error handling.

* Incorrect assumptions.

* Incomplete test coverage.

The reviewer should operate independently from the implementation agent and prioritize evidence-based findings.

Every finding should include:

* Severity.

* Affected location.

* Explanation.

* Reproduction steps when possible.

* Suggested correction.

### 9\. Self-Healing Workflow

When a verification step fails, the system should:

* Classify the failure.

* Analyze the relevant logs.

* Identify the likely root cause.

* Propose a minimal correction.

* Apply the correction within the task workspace.

* Re-run the relevant tests.

* Verify that the correction did not introduce regressions.

Implement strict retry limits and resource budgets.

Never allow infinite repair loops.

### 10\. Visual QA

For frontend projects, allow agents to inspect actual application behavior.

Capabilities:

* Launch the local development server.

* Open the application in a browser.

* Inspect screenshots.

* Detect console errors.

* Test responsive layouts.

* Inspect interactive elements.

* Compare rendered results with expected behavior.

* Verify frontend changes after implementation.

Use existing local browser automation tools where possible.

### 11\. Intent-to-Tests

Convert natural-language requirements into measurable acceptance criteria.

Example:

User request:

"Improve the authentication system without breaking existing API behavior."

OpenCode should:

* Identify ambiguous requirements.

* Define expected behavior.

* Discover existing tests.

* Generate missing acceptance tests.

* Implement the requested change.

* Verify compatibility.

* Report incomplete requirements.

The system must distinguish between satisfying the requested behavior and merely producing code that compiles.

### 12\. Background Engineering

Support long-running local engineering tasks.

Examples:

* Analyze technical debt.

* Identify missing tests.

* Review dependency updates.

* Investigate recurring bugs.

* Prepare refactoring proposals.

* Review local Git branches.

* Generate engineering reports.

Tasks must have resource limits, execution boundaries, and clear completion conditions.

No external server or hosted agent infrastructure should be required.

### 5\. Advanced Differentiating Features

### 13\. Personal Engineering Benchmark

Create a local benchmark system that evaluates available models and agent configurations against real tasks.

Measure:

* Task success rate.

* Regression rate.

* Execution time.

* Token consumption.

* Estimated cost.

* Number of repair attempts.

* Human intervention.

* Verification quality.

Allow developers to compare models on their own projects.

Benchmark results must be reproducible and include model versions, configurations, repository commits, and execution environments.

### 14\. One-Click Delivery

Introduce a complete engineering workflow:

Requirement → Planning → Implementation → Testing → Review → Documentation → Pull Request Preparation.

The final result should include:

* Verified changes.

* Test report.

* Review findings.

* Updated documentation.

* Proposed commit.

* Pull request description.

* Remaining risks.

Production deployment and destructive operations must always require explicit authorization.

### 15\. Engineering Skills Marketplace

Create an open-source ecosystem for reusable engineering workflows.

Examples:

* Safe database migrations.

* Framework upgrades.

* API development.

* Security reviews.

* Performance optimization.

* Frontend accessibility audits.

* Test generation.

* Legacy code modernization.

Every skill should have:

* Versioning.

* Documentation.

* Permission requirements.

* Evaluation tests.

* Compatibility information.

* Quality standards.

Skills should be installable and executable locally without mandatory external services.

### 6\. MVP — Change Assurance

The first release should focus on one highly valuable workflow.

MVP Objective: Safely implement a complex code change and provide verifiable evidence that the change works.

### Required MVP Features

* Capture the initial Git state.

* Protect existing developer modifications.

* Analyze the requested task.

* Generate acceptance criteria.

* Identify affected files and dependencies.

* Create an isolated Git worktree.

* Implement the requested changes.

* Execute relevant tests.

* Run linting and type checking where available.

* Perform an independent diff review.

* Generate a structured verification report.

* Support rollback and recovery.

### Example Workflow

User request:

"Fix token expiration in the authentication service without breaking existing API behavior."

Expected execution:

Step 1 — Project Analysis

* Inspect authentication modules.

* Identify token-related functions.

* Locate dependent APIs.

* Discover existing tests.

Step 2 — Impact Analysis

* Identify potentially affected modules.

* Identify compatibility risks.

* Select relevant test suites.

Step 3 — Task Planning

* Define acceptance criteria.

* Create implementation steps.

* Identify required verification steps.

Step 4 — Isolated Execution

* Create a Git worktree.

* Implement the required changes.

* Preserve the original working tree.

Step 5 — Verification

* Run unit tests.

* Run integration tests.

* Run relevant regression tests.

* Perform code review.

* Check API compatibility.

Step 6 — Final Report

Generate a report containing:

* Modified files.

* Implementation summary.

* Actual test results.

* Verification status.

* Remaining risks.

* Suggested commit.

* Recovery information.

The task should only be marked verified when all mandatory acceptance criteria have sufficient evidence.

### 7\. Twelve-Month Development Roadmap

| Phase   | Timeline     | Main Deliverable                 |
| ------- | ------------ | -------------------------------- |
| Phase 1 | Months 1–2   | Change Assurance                 |
| Phase 2 | Months 3–4   | Project Intelligence             |
| Phase 3 | Months 5–7   | Independent Verification         |
| Phase 4 | Months 8–10  | Controlled Multi-Agent Execution |
| Phase 5 | Months 11–12 | Open Engineering Ecosystem       |

### Phase 1 — Reliability Foundation

* Git workspace isolation.

* Safe execution.

* Evidence Ledger.

* Test execution.

* Recovery mechanisms.

* Accurate task states.

### Phase 2 — Project Understanding

* Incremental code indexing.

* Dependency mapping.

* API contract discovery.

* Test discovery.

* Change impact analysis.

### Phase 3 — Verification

* Independent code review.

* Regression testing.

* Acceptance criteria validation.

* Security checks.

* Verification reports.

### Phase 4 — Multi-Agent Engineering

* Task decomposition.

* Agent specialization.

* Parallel execution.

* Conflict detection.

* Controlled integration.

* Model routing.

### Phase 5 — Ecosystem

* Local plugin SDK.

* Reusable engineering skills.

* Public benchmark suite.

* Developer documentation.

* Community contribution standards.

The roadmap should be adjusted based on implementation complexity and measured results.

### 8\. Reliability Benchmark Suite

Create a dedicated benchmark system to measure real engineering quality.

Use three evaluation categories.

### A. Standard Software Engineering Benchmarks

Evaluate OpenCode on established software engineering tasks.

Examples:

* SWE-bench Verified.

* Real-world bug fixing.

* Feature implementation.

* Refactoring tasks.

* Repository-level reasoning.

### B. Large Codebase Benchmarks

Create a dedicated dataset involving:

* Multi-module projects.

* Legacy systems.

* API compatibility.

* Large-scale refactoring.

* Dependency migrations.

* Cross-module changes.

* Regression-sensitive features.

### C. Reliability Benchmarks

Test the system under failure conditions:

* Interrupted execution.

* Model failures.

* Test failures.

* Git conflicts.

* Existing user modifications.

* Permission violations.

* Malicious repository instructions.

* Recovery after crashes.

Publish reproducible benchmark results for every major release.

### 9\. Success Metrics

Initial proposed targets:

| Metric                       | Target                                 |
| ---------------------------- | -------------------------------------- |
| Verified Task Success Rate   | 10% relative improvement over baseline |
| Regression Rate              | 25% relative reduction                 |
| User Change Preservation     | 100% across recovery test suite        |
| Verification Status Accuracy | 100% across evaluation suite           |
| Cost per Verified Task       | No unjustified increase                |
| Time to Verified Result      | Improvement over baseline              |

Security-critical failures must have independent release-blocking criteria.

Success should be measured through actual repository changes and independent tests, not self-reported agent confidence.

### 10\. Risk Management

| Risk                               | Mitigation                                                        |
| ---------------------------------- | ----------------------------------------------------------------- |
| Excessive architectural complexity | Build incrementally and reuse existing OpenCode infrastructure    |
| Incorrect test results             | Independent verification and acceptance testing                   |
| Lost user modifications            | Git isolation and recovery testing                                |
| Prompt injection                   | Strict separation between untrusted content and security policies |
| Excessive agent coordination       | Parallelize only independent tasks                                |
| Model inconsistency                | Standardized adapters and reproducible evaluations                |
| Large repository performance       | Incremental indexing and caching                                  |
| False confidence                   | Explicit verification states and evidence requirements            |

The most important security principle:

AI agents must never control the security boundaries that restrict their own execution.

11. Open-Source Development Principles
The product must remain genuinely usable without paid infrastructure.
Mandatory Principles
Core execution must work locally.
No mandatory proprietary backend.
No mandatory cloud database.
No mandatory paid API.
Local models must be supported.
User data must remain under user control.
Benchmarking must work locally.
Plugins must have documented permission requirements.
Core verification and recovery must remain open-source.
The evidence format must be portable.
All critical functionality must work without a subscription.
Suggested Technology Strategy
Prefer existing, mature components rather than introducing unnecessary infrastructure.
Git worktrees for workspace isolation.
Existing OpenCode agent and tool systems for execution.
Tree-sitter and language servers for code understanding.
Existing test runners for verification.
SQLite for local structured storage.
Filesystem-based evidence artifacts.
Local browser automation for visual testing.
Existing sandboxing mechanisms supported by the host operating system.
Do not introduce a new database, orchestration framework, or custom execution runtime unless a measurable limitation justifies it.