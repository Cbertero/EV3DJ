package cl.duoc.fasttrack.ordersservice.controller;

import cl.duoc.fasttrack.ordersservice.model.Order;
import cl.duoc.fasttrack.ordersservice.repository.OrderRepository;
import cl.duoc.fasttrack.ordersservice.service.NotificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private NotificationService notificationService;

    // Registrar pedido
    @PostMapping
    public ResponseEntity<Order> createOrder(@RequestBody Order order) {
        // 1. Guardar orden en H2 con estado 'REGISTRADO'
        Order savedOrder = orderRepository.save(order);

        // 2. Publicar evento en SQS de forma asíncrona
        notificationService.sendNotification(savedOrder);

        // 3. Responder de inmediato 201 Created
        return new ResponseEntity<>(savedOrder, HttpStatus.CREATED);
    }

    // Listar pedidos (para validación)
    @GetMapping
    public ResponseEntity<List<Order>> getAllOrders() {
        return new ResponseEntity<>(orderRepository.findAll(), HttpStatus.OK);
    }
}
