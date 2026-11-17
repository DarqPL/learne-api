package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class OrderTests {
    @Test
    void prePersistGeneratesStableVersionFourPublicId() throws Exception {
        Order order = new Order();
        var prePersist = Order.class.getDeclaredMethod("prePersist");
        prePersist.setAccessible(true);

        prePersist.invoke(order);
        UUID publicId = order.getPublicId();

        assertThat(publicId).isNotNull();
        assertThat(publicId.version()).isEqualTo(4);

        prePersist.invoke(order);

        assertThat(order.getPublicId()).isEqualTo(publicId);
    }

    @Test
    void addItemSetsOwningOrder() {
        Order order = new Order();
        OrderItem item = new OrderItem();

        order.addItem(item);

        assertThat(order.getItems()).containsExactly(item);
        assertThat(item.getOrder()).isSameAs(order);
    }
}
