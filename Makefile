CLUSTER_NAME ?= unbroken-chain
UBC_PRESENTATION_IMAGE          ?= unbrokenchain/ubc-control-plane-presentation:latest
GITHUB_GATEWAY_PRESENTATION_IMAGE ?= unbrokenchain/github-gateway-presentation:latest
# Minimum aggregate statement coverage % enforced by `make coverage-check`.
# Baseline at introduction was 72.68%; override with COVERAGE_MIN=NN.
COVERAGE_MIN ?= 70

SCRIPT_DIR := bin

.PHONY: check-deps start stop kubeconfig k9s build-images build-presentations build-ubc-presentation build-github-gateway-presentation load-images deploy-images deploy-app psql-github-gateway psql-control-plane prepare-migrations rabbitmq-ui rabbitmq-queues rabbitmq-exchanges rabbitmq-bindings full-github-gateway full-reader full-writer full-extraction-service full-ubc-control-plane load-github-app-secrets docs-deps docs-serve docs-build verify-deploy-deps coverage coverage-check help

## check-deps : verify all required local tools are installed
check-deps:
	@$(SCRIPT_DIR)/check-deps.sh $(CLUSTER_NAME)

## start : create and start the local k3d cluster
start:
	@$(SCRIPT_DIR)/start-local-env.sh $(CLUSTER_NAME)

## stop : delete the local k3d cluster
stop:
	@$(SCRIPT_DIR)/stop-local-env.sh $(CLUSTER_NAME)

## kubeconfig : set kubectl context to the local k3d cluster
kubeconfig:
	@$(SCRIPT_DIR)/kubeconfig.sh local $(CLUSTER_NAME)

## k9s : launch k9s for the local k3d cluster
k9s:
	@$(SCRIPT_DIR)/k9s.sh $(CLUSTER_NAME)

## build-images : build all service Docker images
build-images:
	./mill provider-gateways.github-gateway.server.docker.build
	./mill reader.server.docker.build
	./mill writer.server.docker.build
	./mill extraction-service.server.docker.build
	./mill ubc-control-plane.server.docker.build
	@$(SCRIPT_DIR)/build-presentation.sh ubc-control-plane/presentation $(UBC_PRESENTATION_IMAGE)
	@$(SCRIPT_DIR)/build-presentation.sh provider-gateways/github-gateway/presentation $(GITHUB_GATEWAY_PRESENTATION_IMAGE)

## build-presentations : build all presentation nginx Docker images
build-presentations:
	@$(SCRIPT_DIR)/build-presentation.sh ubc-control-plane/presentation $(UBC_PRESENTATION_IMAGE)
	@$(SCRIPT_DIR)/build-presentation.sh provider-gateways/github-gateway/presentation $(GITHUB_GATEWAY_PRESENTATION_IMAGE)

## build-ubc-presentation : build the ubc-control-plane presentation nginx image
build-ubc-presentation:
	@$(SCRIPT_DIR)/build-presentation.sh ubc-control-plane/presentation $(UBC_PRESENTATION_IMAGE) $(CLUSTER_NAME)

## build-github-gateway-presentation : build the github-gateway presentation nginx image
build-github-gateway-presentation:
	@$(SCRIPT_DIR)/build-presentation.sh provider-gateways/github-gateway/presentation $(GITHUB_GATEWAY_PRESENTATION_IMAGE) $(CLUSTER_NAME)

## load-images : import all service Docker images into the k3d cluster
load-images:
	@$(SCRIPT_DIR)/load-images.sh $(CLUSTER_NAME)

## deploy-images : build all service Docker images and import them into the k3d cluster
deploy-images: build-images load-images

## deploy-app : deploy the full application stack to the local k3d cluster
deploy-app:
	@$(SCRIPT_DIR)/deploy-local.sh $(CLUSTER_NAME)

## prepare-migrations : build and load all migration images into k3d
prepare-migrations:
	@$(SCRIPT_DIR)/build-migrations.sh provider-gateways/github-gateway unbrokenchain/github-gateway-migrations:latest $(CLUSTER_NAME)
	@$(SCRIPT_DIR)/build-migrations.sh ubc-control-plane unbrokenchain/ubc-control-plane-migrations:latest $(CLUSTER_NAME)

## psql-github-gateway : open psql session for the github_gateway database
psql-github-gateway:
	@$(SCRIPT_DIR)/psql.sh github_gateway

## psql-control-plane : open psql session for the ubc_control_plane database
psql-control-plane:
	@$(SCRIPT_DIR)/psql.sh ubc_control_plane

## rabbitmq-ui : port-forward RabbitMQ management UI to localhost:15672 (prints credentials)
rabbitmq-ui:
	@$(SCRIPT_DIR)/rabbitmq-admin.sh ui $(CLUSTER_NAME)

## rabbitmq-queues : list all queues with message and consumer counts
rabbitmq-queues:
	@$(SCRIPT_DIR)/rabbitmq-admin.sh queues $(CLUSTER_NAME)

## rabbitmq-exchanges : list all exchanges with type and durability
rabbitmq-exchanges:
	@$(SCRIPT_DIR)/rabbitmq-admin.sh exchanges $(CLUSTER_NAME)

## rabbitmq-bindings : list all bindings (source → destination)
rabbitmq-bindings:
	@$(SCRIPT_DIR)/rabbitmq-admin.sh bindings $(CLUSTER_NAME)

## full-github-gateway : build, load, and redeploy the github-gateway service (dev iteration)
full-github-gateway:
	@$(SCRIPT_DIR)/full-deploy-service.sh \
		provider-gateways.github-gateway.server \
		unbrokenchain/github-gateway:latest \
		github-gateway \
		provider-gateways/github-gateway/k8s \
		$(CLUSTER_NAME)

## full-reader : build, load, and redeploy the reader service (dev iteration)
full-reader:
	@$(SCRIPT_DIR)/full-deploy-service.sh \
		reader.server \
		unbrokenchain/reader:latest \
		reader \
		reader/k8s \
		$(CLUSTER_NAME)

## full-writer : build, load, and redeploy the writer service (dev iteration)
full-writer:
	@$(SCRIPT_DIR)/full-deploy-service.sh \
		writer.server \
		unbrokenchain/writer:latest \
		writer \
		writer/k8s \
		$(CLUSTER_NAME)

## full-extraction-service : build, load, and redeploy the extraction-service (dev iteration)
full-extraction-service:
	@$(SCRIPT_DIR)/full-deploy-service.sh \
		extraction-service.server \
		unbrokenchain/extraction-service:latest \
		extraction-service \
		extraction-service/k8s \
		$(CLUSTER_NAME)

## full-ubc-control-plane : build, load, and redeploy the ubc-control-plane service (dev iteration)
full-ubc-control-plane:
	@$(SCRIPT_DIR)/full-deploy-service.sh \
		ubc-control-plane.server \
		unbrokenchain/ubc-control-plane:latest \
		ubc-control-plane \
		ubc-control-plane/k8s \
		$(CLUSTER_NAME)

## docs-deps : sync Python deps for the docs site (uv sync into site/.venv)
docs-deps:
	@$(SCRIPT_DIR)/docs-deps.sh

## docs-serve : serve the docs site locally with live reload
docs-serve:
	@$(SCRIPT_DIR)/docs-serve.sh

## docs-build : build the static docs site into site/_site (strict mode)
docs-build:
	@$(SCRIPT_DIR)/docs-build.sh

## load-github-app-secrets : load PEM/webhook/verifier from .local/ into the local-credentials Secret
load-github-app-secrets:
	@$(SCRIPT_DIR)/load-github-app-secrets.sh

## verify-deploy-deps : fail if any server bundles test-only generators or zio-test
verify-deploy-deps:
	@$(SCRIPT_DIR)/verify-no-test-deps-in-deploy.sh

## coverage : run the instrumented test suite and print an aggregate coverage report (no gate)
coverage:
	@$(SCRIPT_DIR)/coverage.sh

## coverage-check : enforce minimum statement coverage (run ./mill __.test first; override COVERAGE_MIN)
coverage-check:
	@$(SCRIPT_DIR)/check-coverage.sh $(COVERAGE_MIN)

## help : list available targets
help:
	@grep -E '^## ' Makefile | sed 's/## //'
