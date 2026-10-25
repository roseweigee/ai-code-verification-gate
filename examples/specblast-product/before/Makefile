APP_NAME=product
NAMESPACE=product
GCP_PROJECT_ID=momo-microservices-gke
REGISTRY=specblast
REGION=asia-east1

.PHONY: setup build deploy all status logs clean dev run push-gke deploy-sit deploy-uat deploy-prod gke-all

setup:
	kubectl create namespace $(NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -

build:
	eval $$(minikube docker-env) && docker build -t $(APP_NAME):latest .

deploy: setup
	kubectl apply -f k8s/dev/

all: build deploy

status:
	kubectl get pods -n $(NAMESPACE)

logs:
	kubectl logs -n $(NAMESPACE) -l app=$(APP_NAME) --tail=100

clean:
	kubectl delete -f k8s/ --ignore-not-found

dev:
	eval $$(minikube docker-env) && skaffold dev --cache-artifacts=false

run:
	eval $$(minikube docker-env) && skaffold run

push-gke:
	docker buildx build --platform linux/amd64 \
		-t $(REGION)-docker.pkg.dev/$(GCP_PROJECT_ID)/$(REGISTRY)/$(APP_NAME):latest \
		--push .

deploy-sit:
	kubectl apply -f k8s/namespace.yaml && \
	kubectl apply -f k8s/secret.yaml && \
	kubectl apply -f k8s/sit/

deploy-uat:
	kubectl apply -f k8s/namespace.yaml && \
	kubectl apply -f k8s/secret.yaml && \
	kubectl apply -f k8s/uat/

deploy-prod:
	kubectl apply -f k8s/namespace.yaml && \
	kubectl apply -f k8s/secret.yaml && \
	kubectl apply -f k8s/prod/

gke-all: push-gke deploy-sit