# Technical Case — Product Catalog Platform

**Role:** Tech Lead — Cloud & Microservices
**Stage:** Second interview
**Format:** You prepare this in advance and walk us through it in a 45–60 minute conversation.

---

## 1. Before you start

This is not a puzzle with a right answer, and there is no checklist behind it. We have given you a problem and a set of constraints, and left the rest to you — what to build, how to structure it, what deserves your time and what doesn't. Those decisions are the exercise. You are interviewing for a role where you set technical direction, so we would rather see the direction you set than watch you satisfy a specification we wrote.

**Please don't spend more than 4-5 hours on this.** You will not be able to do everything you would want to do, and choosing what to drop is part of what we are interested in — so keep a note of what you left out and why, and put it in your README. An omission you can defend is as informative to us as anything you build.

Where the brief is ambiguous, that is on purpose. Make a decision, write down the assumption, and be ready to explain the trade-off.

---

## 2. The problem

We are building a **product catalog domain** from scratch. It owns three kinds of information about a product, each of which comes from a different upstream system that we do not control:

| Data | Source of truth | How it changes | Shape of the load |
| --- | --- | --- | --- |
| **Product** (identity, descriptive attributes, category, status) | PIM system, PostgreSQL, owned by another team. Exposes a REST API and can emit change events if we ask them to. | Editorial changes, bulk imports from suppliers | ~2M SKUs across 8 countries. ~50k changes/day, but supplier imports arrive as bursts of 100k+ in a few minutes |
| **Price** (base price, currency, active promotions, validity windows) | Third-party pricing SaaS. REST API, rate-limited, plus configurable webhooks. | Repricing engine runs continuously; campaign launches are scheduled | Steady ~200/s, bursts to ~3,000/s at campaign start |
| **Stock** (availability per warehouse, reserved quantity) | Warehouse management system. Already publishes to a Kafka topic. At-least-once delivery, no ordering guarantee across partitions, and it occasionally replays. | Every order, receipt, and stock count | ~1,500/s steady, up to ~10,000/s during peak season |

There are two kinds of consumer:

**a) Another engineering team** (the storefront BFF) needs to read this data. They ask us for "the product, the price and the stock" — how we expose that is our decision to make, not theirs. Their traffic is roughly **500 rps, p95 under 200 ms**, and they are latency-sensitive on the critical rendering path.

**b) A search engine indexer.** Everything — product, price and stock — must reach a search index so the catalog is searchable and filterable. Business expects a change to be visible in search within **30 seconds (p95)**, and we need to be able to **rebuild the whole index from scratch within an hour** without taking the site down.

Our platform runs on **AWS**, and we use an **event-driven architecture** as the default way services communicate.

---

## 3. What to prepare

### Design

Design the solution. Present it however you would present it to a team you were about to lead — diagrams, an ADR, a markdown document, a photo of a whiteboard. We care about the reasoning, not the format.

### A working slice

Build enough of it to be real, within these constraints:

- **The product path only.** Price and stock are design work, not code.
- **The service that owns product data is written in Java / Spring Boot.** Minimal functionality: **upsert a product, and get a product by id.** Nothing more.
- **Keep the product model minimal — but complete.** `id` and `name` are the only descriptive fields we need; this is not a data-modelling exercise. What we do expect is whatever control data a product record genuinely needs to work in a system like this one. We have deliberately not told you what that is: deciding what belongs there, and being able to say why, is part of the exercise.
- **The indexer is written in Go**, and is likewise minimal: consume what the product service publishes, build the search document, and that's it. Go may not be your daily language — that is deliberate, and expected. Our existing services are Spring Boot and we are choosing Go for new ones.
- **The two communicate for real**, over whatever transport you would genuinely choose and can run locally.
- **There is no search engine.** The indexer logs the document it would have sent. Don't integrate a real one.

You do not need to build a UI, authentication, pagination, CI/CD, or infrastructure as code. Everything else is your call.

### Running it

We have given you a **starter repository** with Docker Compose already set up — a Kafka-compatible broker, Postgres if you want it, and a slot for each service. Infrastructure plumbing isn't interesting to us, so don't spend time on it. We just need `docker compose up --build` to work from a clean checkout, and a line in your README telling us how to exercise it.

### How you worked

We expect our engineers to build with AI agents, and to be accountable for what comes out. **Alongside the code, include everything from your working setup that shaped, constrained or reviewed the agent's output** — in whatever form it exists in your repository or workspace, exactly as you actually used it. Please don't tidy it up or reconstruct it for our benefit; the real thing is more interesting to us than a polished one.

Add a short note (half a page is plenty) on which parts you delegated and which you wrote or rewrote yourself, how you satisfied yourself that the output met a standard you would sign your name to, and where the agent got it wrong.

---

## 4. What to send us

A repository or archive with everything above, plus a README covering your assumptions, the things you deliberately left out, your open questions, and what you would do next with another week.

Please send it at least **24 hours before the session** so we can read it properly.

---

## 5. The conversation

Roughly 45–60 minutes: you walk us through what you built, we dig into the design and the decisions behind it, we read some code together, and we talk about how you worked with the agent.

No slides required. Bring opinions, and be ready to disagree with ours.
