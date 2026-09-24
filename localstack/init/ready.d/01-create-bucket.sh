#!/bin/sh
# LocalStack init hook (mounted at /etc/localstack/init/ready.d via
# docker-compose.yml). Creates the attachments bucket if it does not
# exist yet. `awslocal` ships in the localstack image and defaults to
# the test/test credentials LocalStack expects.
set -eu

BUCKET="${S3_BUCKET:-fieldwork-attachments}"
REGION="${S3_REGION:-us-east-1}"

if awslocal s3api head-bucket --bucket "$BUCKET" 2>/dev/null; then
  echo "Bucket s3://$BUCKET already exists"
else
  awslocal s3 mb "s3://$BUCKET" --region "$REGION"
  echo "Created bucket s3://$BUCKET"
fi
