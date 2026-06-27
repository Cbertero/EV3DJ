import json


def lambda_handler(event, context):
    print("[FaaS Notify] Función Serverless iniciada...")

    records = event.get("Records", [])

    if not records:
        print("[FaaS Notify] El evento no contiene registros (Records) para procesar.")
        return {
            'statusCode': 200,
            'body': json.dumps('Sin mensajes para procesar.')
        }

    for record in records:
        try:
            body = record.get("body", "{}")
            order = json.loads(body)

            print("=======================================================")
            print("[FaaS Notify] NUEVO EVENTO DETECTADO EN LA COLA SQS")
            print("=======================================================")
            print(f"Orden ID: {order.get('orderId')}")
            print(f"Código de Seguimiento: {order.get('trackingId')}")
            print(f"Cliente: {order.get('clientName')} ({order.get('clientEmail')})")
            print(f"Destino: {order.get('destination')}")
            print("Detalle: Enviando correo de confirmación de despacho...")
            print("[FaaS Notify] ¡Correo de despacho enviado con éxito!")
            print("=======================================================")

        except (json.JSONDecodeError, AttributeError) as e:
            print(f"[FaaS Notify] ERROR: no se pudo procesar el registro. Detalle: {e}")
            print("Registro recibido:", json.dumps(record))

    return {
        'statusCode': 200,
        'body': json.dumps(f'Procesamiento de {len(records)} notificación(es) finalizado.')
    }
