#!/usr/bin/env bash
set -euo pipefail

DOCS_PATH=${1:-.}
mkdir -p "$DOCS_PATH/docs/description"
if [ ! -d "vendor" ]; then # ensure bundle is installed
    bundle config set --local path 'vendor/bundle'
    bundle install
fi
# Existing docs/ are read to preserve hand-tuned levels, categories and descriptions,
# so they are not deleted before generating.
bundle exec ruby doc_generation/reek/generator.rb "$DOCS_PATH"
