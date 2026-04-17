CLUSTER_NAME ?= unbroken-chain
UBC_PRESENTATION_IMAGE          ?= unbrokenchain/ubc-control-plane-presentation:latest
GITHUB_GATEWAY_PRESENTATION_IMAGE ?= unbrokenchain/github-gateway-presentation:latest

SCRIPT_DIR := bin

.PHONY: check-deps start stop kubeconfig k9s build-images build-presentations build-ubc-presentation build-github-gateway-presentation load-images deploy-images deploy-app psql-github-gateway psql-control-plane prepare-migrations rabbitmq-ui rabbitmq-queues rabbitmq-exchanges rabbitmq-bindings full-github-gateway full-reader full-writer full-extraction-service full-ubc-control-plane

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
	./mill 'provider-gateways.github-gateway.server.docker.build'
	k3d image import unbrokenchain/github-gateway:latest --cluster $(CLUSTER_NAME)
	helm upgrade --install github-gateway provider-gateways/github-gateway/k8s \
		--namespace unbroken-chain --values provider-gateways/github-gateway/k8s/values.yaml --wait
	kubectl rollout restart deployment/github-gateway -n unbroken-chain
	kubectl rollout status deployment/github-gateway -n unbroken-chain --timeout=2m

## full-reader : build, load, and redeploy the reader service (dev iteration)
full-reader:
	./mill 'reader.server.docker.build'
	k3d image import unbrokenchain/reader:latest --cluster $(CLUSTER_NAME)
	helm upgrade --install reader reader/k8s \
		--namespace unbroken-chain --values reader/k8s/values.yaml --wait
	kubectl rollout restart deployment/reader -n unbroken-chain
	kubectl rollout status deployment/reader -n unbroken-chain --timeout=2m

## full-writer : build, load, and redeploy the writer service (dev iteration)
full-writer:
	./mill 'writer.server.docker.build'
	k3d image import unbrokenchain/writer:latest --cluster $(CLUSTER_NAME)
	helm upgrade --install writer writer/k8s \
		--namespace unbroken-chain --values writer/k8s/values.yaml --wait
	kubectl rollout restart deployment/writer -n unbroken-chain
	kubectl rollout status deployment/writer -n unbroken-chain --timeout=2m

## full-extraction-service : build, load, and redeploy the extraction-service (dev iteration)
full-extraction-service:
	./mill 'extraction-service.server.docker.build'
	k3d image import unbrokenchain/extraction-service:latest --cluster $(CLUSTER_NAME)
	helm upgrade --install extraction-service extraction-service/k8s \
		--namespace unbroken-chain --values extraction-service/k8s/values.yaml --wait
	kubectl rollout restart deployment/extraction-service -n unbroken-chain
	kubectl rollout status deployment/extraction-service -n unbroken-chain --timeout=2m

## full-ubc-control-plane : build, load, and redeploy the ubc-control-plane service (dev iteration)
full-ubc-control-plane:
	./mill 'ubc-control-plane.server.docker.build'
	k3d image import unbrokenchain/ubc-control-plane:latest --cluster $(CLUSTER_NAME)
	helm upgrade --install ubc-control-plane ubc-control-plane/k8s \
		--namespace unbroken-chain --values ubc-control-plane/k8s/values.yaml --wait
	kubectl rollout restart deployment/ubc-control-plane -n unbroken-chain
	kubectl rollout status deployment/ubc-control-plane -n unbroken-chain --timeout=2m

## help : list available targets
help:
	@grep -E '^## ' Makefile | sed 's/## //'
