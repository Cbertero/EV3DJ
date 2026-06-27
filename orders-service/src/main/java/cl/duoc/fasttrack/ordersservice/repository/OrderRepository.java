package cl.duoc.fasttrack.ordersservice.repository;

import cl.duoc.fasttrack.ordersservice.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
}
