exports.handler = async (event) => {
    console.log("[FaaS Notify] Función Serverless iniciada...");

    const records = event.Records || [];

    if (records.length === 0) {
        console.log("[FaaS Notify] El evento no contiene registros (Records) para procesar.");
        return {
            statusCode: 200,
            body: JSON.stringify('Sin mensajes para procesar.'),
        };
    }

    for (const record of records) {
        try {
            const order = JSON.parse(record.body);

            console.log("=======================================================");
            console.log("[FaaS Notify] NUEVO EVENTO DETECTADO EN LA COLA SQS");
            console.log("=======================================================");
            console.log(`Orden ID: ${order.orderId}`);
            console.log(`Código de Seguimiento: ${order.trackingId}`);
            console.log(`Cliente: ${order.clientName} (${order.clientEmail})`);
            console.log(`Destino: ${order.destination}`);
            console.log("Detalle: Enviando correo de confirmación de despacho...");
            console.log("[FaaS Notify] ¡Correo de despacho enviado con éxito!");
            console.log("=======================================================");

        } catch (err) {
            console.error(`[FaaS Notify] ERROR: no se pudo procesar el registro. Detalle: ${err.message}`);
            console.error("Registro recibido:", JSON.stringify(record));
        }
    }

    return {
        statusCode: 200,
        body: JSON.stringify(`Procesamiento de ${records.length} notificación(es) finalizado.`),
    };
};
