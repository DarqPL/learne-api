package com.learne.backend.monetization;

import static org.assertj.core.api.Assertions.assertThat;

import com.learne.backend.monetization.entity.Order;
import com.learne.backend.monetization.entity.OrderItem;
import org.junit.jupiter.api.Test;

class OrderTests {
    @Test
    void addItemSetsOwningOrder() {
        Order order = new Order();
        OrderItem item = new OrderItem();

        order.addItem(item);

        assertThat(order.getItems()).containsExactly(item);
        assertThat(item.getOrder()).isSameAs(order);
    }
}
