#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "$0")/.." && pwd)"
tiqian_dir="${TIQIAN_CHECKOUT:-$(cd "$root_dir/../tiqian" && pwd)}"
repository="$(mktemp -d "${TMPDIR:-/tmp}/tiqian-readme-sample.XXXXXX")"
trap 'rm -rf "$repository"' EXIT

tiqian_modules=(
  core
  font
  linebreak
  clreq
  shaping:api
  layout
  shaping:skia
)
publish_tasks=()
for module in "${tiqian_modules[@]}"; do
  publish_tasks+=(
    ":$module:publishJvmPublicationToMavenLocal"
    ":$module:publishKotlinMultiplatformPublicationToMavenLocal"
  )
done

"$tiqian_dir/gradlew" -p "$tiqian_dir" \
  -Dmaven.repo.local="$repository" \
  "${publish_tasks[@]}"

"$root_dir/gradlew" -p "$root_dir" \
  -Dmaven.repo.local="$repository" \
  -PtiqianSampleRepository="$repository" \
  :readme-sample:generateReadmeSample
