package cl.duoc.fasttrack.ordersservice.service;

import cl.duoc.fasttrack.ordersservice.model.Order;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.time.Instant;

@Service
public class NotificationService {

    @Value("${aws.sqs.endpoint}")
    private String sqsEndpoint;

    @Value("${aws.region}")
    private String awsRegion;

    @Value("${aws.sqs.queue-name}")
    private String queueName;

    private SqsClient sqsClient;
    private String queueUrl;

    @PostConstruct
    public void init() {
        // Cliente SQS apuntando a LocalStack, con credenciales dummy
        // (LocalStack no valida credenciales reales en modo local/free).
        this.sqsClient = SqsClient.builder()
                .endpointOverride(URI.create(sqsEndpoint))
                .region(Region.of(awsRegion))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy-access-key", "dummy-secret-key")
                ))
                .build();

        this.queueUrl = resolveQueueUrl();
        System.out.println("[Productor] Cliente SQS inicializado. Queue URL: " + queueUrl);
    }

    /**
     * Obtiene el URL de la cola; si no existe (primer arranque en LocalStack),
     * la crea para que el flujo funcione sin pasos manuales adicionales.
     */
    private String resolveQueueUrl() {
        try {
            return sqsClient.getQueueUrl(GetQueueUrlRequest.builder()
                            .queueName(queueName)
                            .build())
                    .queueUrl();
        } catch (QueueDoesNotExistException e) {
            System.out.println("[Productor] La cola '" + queueName + "' no existe aún. Creándola...");
            return sqsClient.createQueue(CreateQueueRequest.builder()
                            .queueName(queueName)
                            .build())
                    .queueUrl();
        }
    }

    public void sendNotification(Order order) {
        System.out.println("[Productor] Publicando evento de orden #" + order.getId() + " en la cola SQS...");

        String jsonBody = String.format(
                "{\"orderId\": %d, \"trackingId\": \"%s\", \"clientName\": \"%s\", \"clientEmail\": \"%s\", " +
                        "\"destination\": \"%s\", \"product\": \"%s\", \"price\": %s, \"status\": \"PROCESANDO_ENVIO\", \"timestamp\": \"%s\"}",
                order.getId(),
                order.getTrackingId(),
                order.getClientName(),
                order.getClientEmail(),
                order.getDestination(),
                order.getProduct(),
                order.getPrice(),
                Instant.now().toString()
        );

        SendMessageRequest request = SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(jsonBody)
                .build();

        sqsClient.sendMessage(request);

        System.out.println("[Productor] Mensaje JSON enviado correctamente para Orden: " + order.getTrackingId());
    }

    @PreDestroy
    public void shutdown() {
        if (sqsClient != null) {
            sqsClient.close();
        }
    }
}
