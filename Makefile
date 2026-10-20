# One entry point for both stacks. Run `make help` for the list.
.DEFAULT_GOAL := help
.PHONY: help up down db backend frontend install hooks test lint check clean

help: ## Show available commands
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'

up: ## Build and run the full stack in Docker (postgres, backend, frontend)
	docker compose up --build

down: ## Stop the Docker stack (data volume is kept)
	docker compose down

db: ## Start only Postgres in Docker, for running backend/frontend locally
	docker compose up -d postgres

backend: ## Run the backend locally against the Docker Postgres (reads .env if present)
	set -a; [ -f .env ] && . ./.env; set +a; \
	cd backend && SPRING_PROFILES_ACTIVE=local ./gradlew bootRun

frontend: ## Run the frontend dev server
	cd frontend && npm run dev

install: hooks ## Install frontend dependencies and the git hooks
	cd frontend && npm ci

hooks: ## Enable the repo's git hooks (.githooks): checks run before every commit and push
	git config core.hooksPath .githooks
	@echo "Git hooks enabled (.githooks/pre-commit, .githooks/pre-push)."

test: ## Run backend tests (needs Docker for Testcontainers)
	cd backend && ./gradlew test

lint: ## Lint and typecheck the frontend
	cd frontend && npm run lint && npm run typecheck

check: test ## Everything CI would run: backend tests + frontend lint, typecheck, tests, build
	cd frontend && npm run check

clean: ## Remove build outputs
	cd backend && ./gradlew clean
	rm -rf frontend/.next
