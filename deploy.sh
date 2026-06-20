#!/bin/bash
# =====================================================
# eNet MQTT Gateway — Root Deploy Script
# Conforms to Hostrup.org global agent guidelines
# =====================================================
set -e

MSG="${1:-"Update eNet MQTT Gateway"}"

echo "🚀 Starting Deployment & CI/CD pipeline..."
echo "Message: $MSG"
echo ""

# 1. Run local build and upload
bash scripts/deploy.sh

# 2. Git Commit & Push
echo ""
echo "🐙 Committing changes to Git..."
git add .
git commit -m "$MSG"

echo ""
echo "⬆️ Pushing to GitHub..."
git push origin main

echo ""
echo "🎉 Deployment pipeline successfully complete!"
