APP_NAME=card-auth-precheck
NAMESPACE=default

.PHONY: setup build deploy all status logs clean dev run

## 建立 namespace（避免重複建立錯誤）
setup:
	kubectl create namespace $(NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -

## 建置 Docker image（使用 minikube Docker daemon）
build:
	eval $$(minikube docker-env) && docker build -t $(APP_NAME):latest .

## 部署到本機 minikube
deploy: setup
	kubectl apply -f k8s/dev/

## 完整建置並部署
all: build deploy

## 查看 Pod 狀態
status:
	kubectl get pods -n $(NAMESPACE)

## 查看最近 100 行 Log（PCI DSS：確認 PAN 未出現在 Log）
logs:
	kubectl logs -n $(NAMESPACE) -l app=$(APP_NAME) --tail=100

## 清除所有 K8s 資源
clean:
	kubectl delete -f k8s/ --ignore-not-found

## 使用 skaffold dev 進行本機開發（熱重載）
dev:
	eval $$(minikube docker-env) && skaffold dev --cache-artifacts=false

## 使用 skaffold run 部署到本機
run:
	eval $$(minikube docker-env) && skaffold run