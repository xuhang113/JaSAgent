#!/usr/bin/env bash
# 准备 Java 21 镜像。jar 来自 mvn -pl jasagent-app -am -DskipTests package。
set -euo pipefail
source "$(cd "$(dirname "$0")" && pwd)/common.sh"

if [[ "${JASAGENT_REBUILD:-}" != "1" ]] \
  && docker image inspect "$JASAGENT_IMAGE_AGENT" "$JASAGENT_IMAGE_GATEWAY" "$JASAGENT_IMAGE_AGENTPASS" "$JASAGENT_IMAGE_RAG" >/dev/null 2>&1; then
  echo "镜像已在：${JASAGENT_IMAGE_AGENT} ${JASAGENT_IMAGE_GATEWAY} ${JASAGENT_IMAGE_AGENTPASS} ${JASAGENT_IMAGE_RAG}"
  echo "要重做镜像时设置 JASAGENT_REBUILD=1。"
  exit 0
fi

staging="$JASAGENT_ROOT/deploy/docker/staging"
mkdir -p "$staging"
arch="$(docker version --format '{{.Server.Arch}}')"
case "$arch" in
  arm64)
    ubuntu_arch=arm64
    adoptium_arch=aarch64
    ;;
  amd64)
    ubuntu_arch=amd64
    adoptium_arch=x64
    ;;
  *)
    echo "不支持的 Docker 架构：$arch" >&2
    exit 1
    ;;
esac

base="$staging/ubuntu-base.tar.gz"
if [[ ! -s "$base" ]]; then
  url="https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-${ubuntu_arch}.tar.gz"
  echo "下载 Ubuntu 24.04 base（${ubuntu_arch}）。"
  curl -fL --retry 3 --retry-delay 2 -o "$base.partial" "$url" \
    || curl -fL --retry 3 --retry-delay 2 -o "$base.partial" \
      "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-${ubuntu_arch}.tar.gz"
  mv "$base.partial" "$base"
fi

jre_tar="$staging/jre.tar.gz"
if [[ ! -d "$staging/jre/bin" ]]; then
  if [[ ! -s "$jre_tar" ]]; then
    echo "下载 Temurin 21 JRE（${adoptium_arch}）。"
    jre_name="OpenJDK21U-jre_${adoptium_arch}_linux_hotspot_21.0.13_9.tar.gz"
    curl -fL --retry 3 --retry-delay 2 -o "$jre_tar.partial" \
      "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jre/${adoptium_arch}/linux/${jre_name}" \
      || curl -fL --retry 3 --retry-delay 2 -o "$jre_tar.partial" \
        "https://api.adoptium.net/v3/binary/latest/21/ga/linux/${adoptium_arch}/jre/hotspot/normal/eclipse?project=jdk"
    mv "$jre_tar.partial" "$jre_tar"
  fi
  rm -rf "$staging/jre"
  mkdir -p "$staging/jre"
  tar -xzf "$jre_tar" -C "$staging/jre" --strip-components=1
fi

jar=""
if [[ -d "$JASAGENT_ROOT/jasagent-app/target" ]]; then
  jar="$(find "$JASAGENT_ROOT/jasagent-app/target" -maxdepth 1 -type f -name 'jasagent-app-*.jar' ! -name '*.jar.original' | sort | tail -n 1 || true)"
fi
if [[ -z "$jar" ]]; then
  if ! command -v mvn >/dev/null 2>&1; then
    echo "找不到 jasagent-app 的 jar，也没有 mvn。" >&2
    exit 1
  fi
  java_home="${JAVA_HOME:-}"
  if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
    java_home=/opt/homebrew/opt/openjdk@21
  fi
  if [[ -z "$java_home" ]]; then
    echo "打包需要 Java 21。" >&2
    exit 1
  fi
  echo "打包 jasagent-app。"
  (cd "$JASAGENT_ROOT" && JAVA_HOME="$java_home" PATH="$java_home/bin:$PATH" mvn -pl jasagent-app -am -DskipTests package)
  jar="$(find "$JASAGENT_ROOT/jasagent-app/target" -maxdepth 1 -type f -name 'jasagent-app-*.jar' ! -name '*.jar.original' | sort | tail -n 1)"
fi
cp "$jar" "$staging/jasagent-app.jar"
cp "$JASAGENT_ROOT/deploy/docker/entrypoint.sh" "$staging/entrypoint.sh"
chmod 755 "$staging/entrypoint.sh"
cat > "$staging/.dockerignore" <<'EOF'
*
!ubuntu-base.tar.gz
!jre
!jre/**
!jasagent-app.jar
!entrypoint.sh
EOF

build_one() {
  local image="$1"
  local role="$2"
  local port="$3"
  docker build \
    -f "$JASAGENT_ROOT/deploy/docker/Dockerfile" \
    --build-arg "JASAGENT_ROLE=${role}" \
    --build-arg "JASAGENT_PORT=${port}" \
    -t "$image" \
    "$staging"
}

build_one "$JASAGENT_IMAGE_AGENT" agent 8080
build_one "$JASAGENT_IMAGE_GATEWAY" gateway 18721
build_one "$JASAGENT_IMAGE_AGENTPASS" agentpass 18741
build_one "$JASAGENT_IMAGE_RAG" rag 18751
echo "镜像：${JASAGENT_IMAGE_AGENT} ${JASAGENT_IMAGE_GATEWAY} ${JASAGENT_IMAGE_AGENTPASS} ${JASAGENT_IMAGE_RAG}"
