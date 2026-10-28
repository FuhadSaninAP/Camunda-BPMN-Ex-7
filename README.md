# Leave Application System (Camunda 8 + Spring Boot + React + Docker)

Employees apply for leave in a React portal. Spring Boot stores the request and starts a
Camunda 8 process. A job worker validates it, a manager approves or rejects it, and a second
worker finalises the status and the annual-leave balance.

## Architecture

The browser talks only to Spring Boot. Spring Boot starts process instances and completes
user tasks through Camunda, and its job workers run the automated service tasks.

| Component | Responsibility | Port |
|---|---|---|
| React frontend | Leave form, request tracking, manager inbox | 5173 (dev), 3000 (Docker) |
| Spring Boot backend | REST API, business rules, database, Camunda workers | 8081 |
| Camunda 8 Self-Managed | Runs the BPMN, user tasks, Operate, Tasklist | REST/UI 8080, gRPC 26500 |
| Database | Leave requests and balances | H2 (dev), PostgreSQL 5432 (Docker) |

## Project structure

```
leave-system/
├── bpmn/                  Step 1: leave-request.bpmn, leave-approval.form
├── frontend/              Step 2: React + Vite + TypeScript
├── backend/               Step 3: Spring Boot (Java 21)
│   └── src/main/resources/bpmn/   copy of the Step 1 files, deployed on startup
├── camunda/               Camunda 8 for local development (Step 4)
└── docker-compose.yml     Step 5: full stack
```

## Roadmap

| Step | What | Folder |
|---|---|---|
| 1 | Design the BPMN process and approval form | `bpmn/` |
| 2 | Build the React frontend | `frontend/` |
| 3 | Build the Spring Boot backend | `backend/` |
| 4 | Integrate and test | `camunda/` + all |
| 5 | Deploy with Docker | `docker-compose.yml` |

---

## Step 1: BPMN process and form (`bpmn/`)

- **Process:** ID `leave-request`, name "Leave request", executable.
- **Flow:** Leave requested -> **Validate leave request** (service task, type `validate-leave`)
  -> **Request valid?** gateway -> **Manager approval** (user task, form `leave-approval`)
  -> **Approved?** gateway -> **Finalize as approved** (type `finalize-leave`, input `outcome = "APPROVED"`)
  -> end *Leave approved*.
- **Rejection path:** the default flows of both gateways go to a **Merge** gateway, then
  **Finalize as rejected** (type `finalize-leave`, input `outcome = "REJECTED"`) -> end *Leave rejected*.
- **Conditions:** `=valid` and `=approved`.
- **Form `leave-approval`:** checkbox `approved`, text area `managerComment`.

| Variable(s) | Set by | Used by |
|---|---|---|
| `requestId, employeeEmail, leaveType, days, startDate, endDate` | Spring Boot at start | job workers |
| `valid, validationMessage` | validate-leave worker | "Request valid?" gateway, rejection |
| `approved, managerComment` | manager task | "Approved?" gateway, finalize worker |
| `outcome` | input mapping on each finalize task | finalize-leave worker |

## Step 2: React frontend (`frontend/`)

- Vite + React + TypeScript with three views: **Apply**, **My requests**, **Manager inbox**.
- `vite.config.ts` proxies `/api` to `http://localhost:8081`.
- React never calls Camunda directly; it only uses the backend API:

| Method and path | Purpose |
|---|---|
| `POST /api/leaves` | Submit a leave request |
| `GET /api/leaves?employeeEmail=...` | List an employee's requests |
| `GET /api/leaves/pending` | Requests waiting for approval |
| `GET /api/leaves/balance?employeeEmail=...` | Remaining annual leave |
| `POST /api/leaves/{id}/decision` | Approve or reject (`approved`, `comment`) |

Run it: `cd frontend && npm install && npm run dev` then open http://localhost:5173.

## Step 3: Spring Boot backend (`backend/`)

- Java 21, Spring Boot 3.5, Spring Web, Data JPA, Validation, H2 (PostgreSQL for Docker),
  and the Camunda Spring Boot starter.
- `@Deployment` in `LeaveApplication` deploys `bpmn/*.bpmn` and `*.form` on startup.
- `LeaveService`: `submit` (save, then start the process), `decide` (complete the manager task),
  `validate` (dates and balance), `finalizeRequest` (idempotent status and balance update), `balance`.
- `LeaveWorkers`: `@JobWorker` types `validate-leave` and `finalize-leave` (must match the BPMN).
- `application.yml`: port 8081, H2 file database, Camunda at `localhost:8080` / `26500`.

Run it: `cd backend && mvn clean package && mvn spring-boot:run`

**Version check:** the Step 3 guide uses `camunda-spring-boot-starter` 8.8.9 and the dependency
guide uses `camunda-spring-boot-3-starter` 8.9.12. This project uses the latter
(`camunda.version` in `backend/pom.xml`). Keep it in line with the Camunda image tag, and
confirm the project compiles for your installed version.

## Step 4: Integration and testing

Start in this order (three terminals):

```bash
cd camunda && docker compose up -d        # 1. Camunda
cd backend && mvn spring-boot:run         # 2. Spring Boot
cd frontend && npm run dev                # 3. React
```

Check: Operate http://localhost:8080/operate shows **Leave request**; Tasklist is at
`/tasklist`; H2 console http://localhost:8081/h2-console (JDBC URL `jdbc:h2:file:./data/leavedb`, user `sa`).

| Test | Steps | Expected |
|---|---|---|
| A. Approved | Annual leave, 3 weekdays; approve in Manager inbox | APPROVED, balance 20 -> 17 |
| B. Manager rejection | Valid request; Reject with a comment | REJECTED, balance unchanged |
| C. Insufficient balance | Annual request above remaining days | Rejected before manager step, message shown |
| D. Weekend only | Saturday to Sunday | Rejected, no working days |
| E. Tasklist approval | Complete "Manager approval" in Tasklist | Process finishes, APPROVED |
| F. Invalid dates | POST an end date before the start date | Rejected, no balance change |

```bash
curl -X POST http://localhost:8081/api/leaves -H "Content-Type: application/json" \
  -d '{"employeeName":"Alice","employeeEmail":"alice@example.com","leaveType":"ANNUAL","startDate":"2026-11-02","endDate":"2026-11-04","reason":"Personal leave"}'
```

In Operate, open the instance to see the executed path and variables (`requestId`, `valid`,
`validationMessage`, `approved`, `managerComment`, `outcome`).

## Step 5: Docker deployment

```bash
docker compose up -d --build
```

| Service | URL |
|---|---|
| Frontend (Nginx) | http://localhost:3000 |
| Backend | http://localhost:8081 |
| Camunda (Operate / Tasklist) | http://localhost:8080/operate , /tasklist |
| PostgreSQL | localhost:5432 (db `leavedb`, user `leave`) |

- `backend/Dockerfile`: multi-stage Maven build, then a Java 21 JRE image.
- `frontend/Dockerfile`: multi-stage Node build, served by Nginx (which proxies `/api` to the backend).
- The backend runs with the `docker` profile (`application-docker.yml`), using PostgreSQL and
  Camunda at `camunda:8080` / `camunda:26500`.
- Set `CAMUNDA_VERSION` (default `8.9.0`) to a Camunda tag that exists and matches the starter.

## Security note

Learning MVP only. There is **no authentication or role check**, so anyone who can reach the
API can use the manager inbox and decision endpoint. The Camunda containers run with
`CAMUNDA_SECURITY_AUTHENTICATION_UNPROTECTED=true` for local use. Add authentication,
authorisation, secret management and backups before exposing this to a network.
