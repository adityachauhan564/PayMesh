#!/usr/bin/env sh
# Runs the whole stack on a local k3d cluster (k3s in Docker) with the Helm chart in deploy/helm/paymesh.
# Needs: Docker, k3d, helm, kubectl, and a .env (copy .env.example). App: http://localhost:8080
# Re-run it after code changes: it rebuilds and re-imports the images and restarts the services.
# Remove everything: k3d cluster delete paymesh
set -e
cd "$(dirname "$0")/.."
NS=paymesh
STRIMZI_VERSION=1.2.0

[ -f .env ] || { echo "No .env: copy .env.example to .env first" >&2; exit 1; }

if ! k3d cluster list paymesh >/dev/null 2>&1; then
  # Traefik (bundled) listens on the load balancer's port 80, published on localhost:8080
  k3d cluster create paymesh --agents 0 -p "127.0.0.1:8080:80@loadbalancer" --wait
fi
kubectl config use-context k3d-paymesh >/dev/null
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

helm upgrade --install strimzi oci://quay.io/strimzi-helm/strimzi-kafka-operator --version "$STRIMZI_VERSION" \
  -n "$NS" --set resources.requests.memory=256Mi --set resources.limits.memory=384Mi --wait

sh scripts/build-images.sh
k3d image import -c paymesh \
  paymesh-user-service paymesh-wallet-service paymesh-transaction-service \
  paymesh-notification-service paymesh-api-gateway paymesh-wallet-ui

# Local secrets come straight from .env (in AWS they come from SSM through External Secrets, Phase 8)
kubectl -n "$NS" create secret generic paymesh-secrets --from-env-file=.env --dry-run=client -o yaml | kubectl apply -f - >/dev/null

helm upgrade --install paymesh deploy/helm/paymesh -n "$NS"
# Same tag ("latest") after a rebuild: restart so the pods pick up the freshly imported images
kubectl -n "$NS" rollout restart deployment -l app.kubernetes.io/part-of=paymesh >/dev/null
kubectl -n "$NS" rollout status statefulset/mysql --timeout=5m
for d in user-service wallet-service transaction-service notification-service api-gateway wallet-ui; do
  kubectl -n "$NS" rollout status "deployment/$d" --timeout=10m
done
echo "PayMesh is up: http://localhost:8080   Grafana: kubectl -n $NS port-forward svc/lgtm 3000:3000"
