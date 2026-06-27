"""
sqs_trigger_poller.py

LocalStack Community no permite configurar un Event Source Mapping nativo
(Lambda <- SQS) como en AWS real, esa característica requiere LocalStack Pro.

Este script cumple el mismo rol que el "trigger" en un entorno productivo:
hace long-polling sobre la cola SQS y, por cada mensaje recibido, invoca la
función serverless (lambda_handler) con el mismo formato de evento que
usaría AWS Lambda al recibir un evento de SQS (event['Records']).

Uso:
    python3 sqs_trigger_poller.py

Requiere:
    pip install boto3 --break-system-packages
"""

import json
import time

import boto3

from lambda_function import lambda_handler

SQS_ENDPOINT = "http://localhost:4566"
AWS_REGION = "us-east-1"
QUEUE_NAME = "fasttrack-notification-queue"

sqs = boto3.client(
    "sqs",
    endpoint_url=SQS_ENDPOINT,
    region_name=AWS_REGION,
    aws_access_key_id="dummy-access-key",
    aws_secret_access_key="dummy-secret-key",
)


def get_or_create_queue_url():
    try:
        return sqs.get_queue_url(QueueName=QUEUE_NAME)["QueueUrl"]
    except sqs.exceptions.QueueDoesNotExist:
        print(f"[Trigger] La cola '{QUEUE_NAME}' no existe aún, creándola...")
        return sqs.create_queue(QueueName=QUEUE_NAME)["QueueUrl"]


def poll_loop():
    queue_url = get_or_create_queue_url()
    print(f"[Trigger] Escuchando la cola SQS en: {queue_url}")
    print("[Trigger] Esperando mensajes (Ctrl+C para detener)...\n")

    while True:
        response = sqs.receive_message(
            QueueUrl=queue_url,
            MaxNumberOfMessages=10,
            WaitTimeSeconds=10,  # long polling
        )

        messages = response.get("Messages", [])
        if not messages:
            continue

        # Construye el mismo formato de evento que AWS Lambda entrega
        # cuando el trigger es una cola SQS.
        event = {
            "Records": [
                {"body": m["Body"], "messageId": m["MessageId"]}
                for m in messages
            ]
        }

        lambda_handler(event, None)

        # Borra los mensajes ya procesados para que no se reprocesen.
        for m in messages:
            sqs.delete_message(QueueUrl=queue_url, ReceiptHandle=m["ReceiptHandle"])


if __name__ == "__main__":
    try:
        poll_loop()
    except KeyboardInterrupt:
        print("\n[Trigger] Detenido manualmente.")
