#!/bin/bash
set -e

APP_NAME="product"
GCP_PROJECT_ID="momo-microservices-gke"
CLUSTER_NAME="momo-cluster"
NAMESPACE="product"
REGION="asia-east1"
REGISTRY="specblast"
SQL_INSTANCE="product-db"
DB_NAME="productdb"
IMAGE="asia-east1-docker.pkg.dev/${GCP_PROJECT_ID}/${REGISTRY}/${APP_NAME}:latest"

# 檢查必要工具
echo "🔍 檢查必要工具..."
if ! command -v gcloud &> /dev/null; then
  echo "❌ 請先安裝 gcloud CLI: https://cloud.google.com/sdk/docs/install"; exit 1
fi
if ! command -v kubectl &> /dev/null; then
  echo "❌ 請先安裝 kubectl"; exit 1
fi
if ! command -v docker &> /dev/null; then
  echo "❌ 請先安裝 Docker"; exit 1
fi
if ! command -v gh &> /dev/null; then
  echo "⚠️  GitHub CLI 未安裝，跳過 GitHub 相關步驟（brew install gh）"
  SKIP_GITHUB=true
fi
if ! gcloud auth list --filter=status:ACTIVE --format="value(account)" 2>/dev/null | grep -q "@"; then
  echo "⚠️  請登入 GCP："; gcloud auth login
fi
if [ "$SKIP_GITHUB" != "true" ] && ! gh auth status 2>/dev/null; then
  echo "⚠️  請登入 GitHub："; gh auth login
fi
echo "✅ 工具檢查完成"
echo ""
echo "🚀 SpecBlast GKE 完整初始化開始..."
echo "   APP: $APP_NAME"
echo "   PROJECT: $GCP_PROJECT_ID"
echo "   CLUSTER: $CLUSTER_NAME"
echo ""

echo "📦 Step 1: 開啟 GCP API..."
gcloud services enable container.googleapis.com secretmanager.googleapis.com artifactregistry.googleapis.com sqladmin.googleapis.com --project=$GCP_PROJECT_ID --quiet
echo "✅ API 已開啟"

echo "🐳 Step 2: 建立 Artifact Registry..."
gcloud artifacts repositories create $REGISTRY --repository-format=docker --location=$REGION --project=$GCP_PROJECT_ID 2>/dev/null || echo "（已存在，跳過）"

echo "☸️  Step 3: 連結 GKE cluster..."
gcloud container clusters get-credentials $CLUSTER_NAME --region=$REGION --project=$GCP_PROJECT_ID
echo "✅ 連結成功"

echo "🔐 Step 4: 設定 GKE 拉 image 權限..."
PROJECT_NUMBER=$(gcloud projects describe $GCP_PROJECT_ID --format='value(projectNumber)')
gcloud projects add-iam-policy-binding $GCP_PROJECT_ID   --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"   --role="roles/artifactregistry.reader" --quiet
echo "✅ 權限設定完成"

echo "📦 Step 5: 建立 namespace..."
kubectl apply -f k8s/namespace.yaml
echo "✅ namespace 建立完成"

echo "🔑 Step 6: 設定 Workload Identity..."
gcloud iam service-accounts create ${APP_NAME}-sa --project=$GCP_PROJECT_ID 2>/dev/null || echo "（已存在，跳過）"
gcloud projects add-iam-policy-binding $GCP_PROJECT_ID   --member="serviceAccount:${APP_NAME}-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com"   --role="roles/secretmanager.secretAccessor" --quiet
gcloud iam service-accounts add-iam-policy-binding ${APP_NAME}-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com   --role="roles/iam.workloadIdentityUser"   --member="serviceAccount:${GCP_PROJECT_ID}.svc.id.goog[${NAMESPACE}/${APP_NAME}-sa]"   --project=$GCP_PROJECT_ID --quiet
kubectl apply -f k8s/serviceaccount.yaml
kubectl annotate serviceaccount ${APP_NAME}-sa -n $NAMESPACE   iam.gke.io/gcp-service-account=${APP_NAME}-sa@${GCP_PROJECT_ID}.iam.gserviceaccount.com --overwrite
echo "✅ Workload Identity 設定完成"

echo "🗄️  Step 7: 設定 Cloud SQL 網路..."
gcloud sql instances patch $SQL_INSTANCE --authorized-networks=0.0.0.0/0 --project=$GCP_PROJECT_ID --quiet || echo "（已設定或跳過）"
echo "✅ Cloud SQL 網路設定完成"

echo "🔨 Step 8: Build + Push image（linux/amd64）..."
gcloud auth configure-docker asia-east1-docker.pkg.dev --quiet
docker buildx build --platform linux/amd64 -t $IMAGE --push .
echo "✅ Image push 完成"

echo "🔑 Step 9: 從 Secret Manager 讀取密碼..."
DB_HOST=$(gcloud secrets versions access latest --secret="${APP_NAME}-db-host" --project=$GCP_PROJECT_ID)
DB_USERNAME=$(gcloud secrets versions access latest --secret="${APP_NAME}-db-username" --project=$GCP_PROJECT_ID)
DB_PASSWORD=$(gcloud secrets versions access latest --secret="${APP_NAME}-db-password" --project=$GCP_PROJECT_ID)
echo "✅ 密碼讀取成功"

echo "📊 Step 10: 建立資料表..."
mysql -h $DB_HOST -u $DB_USERNAME -p"$DB_PASSWORD" $DB_NAME < src/main/resources/migration.sql
mysql -h $DB_HOST -u $DB_USERNAME -p"$DB_PASSWORD" $DB_NAME < src/main/resources/data.sql 2>/dev/null || true
echo "✅ 資料表建立完成"

echo "🔑 Step 11: 建立 K8s Secret..."
kubectl create secret generic ${APP_NAME}-db-secret   --from-literal=DB_HOST=$DB_HOST   --from-literal=DB_NAME=$DB_NAME   --from-literal=DB_USERNAME=$DB_USERNAME   --from-literal=DB_PASSWORD=$DB_PASSWORD   -n $NAMESPACE   --dry-run=client -o yaml | kubectl apply -f -
echo "✅ K8s Secret 建立完成"

echo "🔧 Step 12: 修正 deployment.yaml image 路徑..."
sed -i.bak "s|asia-east1-docker.pkg.dev/${GCP_PROJECT_ID}/${APP_NAME}/${APP_NAME}:latest|${IMAGE}|g" k8s/sit/deployment.yaml 2>/dev/null || true
echo "✅ image 路徑修正完成"

echo "🚀 Step 13: 部署到 GKE SIT..."
kubectl apply -f k8s/sit/deployment.yaml
kubectl rollout status deployment/$APP_NAME -n $NAMESPACE --timeout=180s

echo "🧪 Step 14: 測試 API..."
lsof -ti:9090 | xargs kill -9 2>/dev/null || true
kubectl port-forward svc/$APP_NAME 9090:8080 -n $NAMESPACE &
PF_PID=$!
sleep 5
curl -s http://localhost:9090/actuator/health | python3 -m json.tool || echo "Health check 失敗"
kill $PF_PID 2>/dev/null || true

echo ""
echo "🐙 Step 15: 推上 GitHub + 設定 Cloud Build Trigger..."
if [ "$SKIP_GITHUB" != "true" ]; then
  printf "GitHub username: "; read GITHUB_USERNAME
  if [ ! -d ".git" ]; then
    git init
    git add .
    git commit -m "init: SpecBlast generated project"
  fi
  gh repo create ${APP_NAME} --public --source=. --push 2>/dev/null || {
    git remote set-url origin https://github.com/$GITHUB_USERNAME/${APP_NAME}.git 2>/dev/null ||     git remote add origin https://github.com/$GITHUB_USERNAME/${APP_NAME}.git
    git push origin main --force 2>/dev/null || true
    echo "（repo 已存在，直接 push）"
  }
  echo ""
  echo "⚠️  Cloud Build Trigger 需要手動在 GCP Console 設定："
  echo "   1. 開啟 https://console.cloud.google.com/cloud-build/triggers?project=$GCP_PROJECT_ID"
  echo "   2. 點「建立觸發條件」→ 連結 GitHub repo：$GITHUB_USERNAME/${APP_NAME}"
  echo "   3. 分支：^main$，建構設定檔：cloudbuild.yaml"
  echo "✅ GitHub 推送完成"
fi

echo ""
echo "🎉 全部完成！"
echo "   kubectl port-forward svc/$APP_NAME 9090:8080 -n $NAMESPACE"
echo "   curl -s http://localhost:9090/api/products | python3 -c 'import sys,json; print(json.dumps(json.load(sys.stdin), ensure_ascii=False, indent=2))'"
