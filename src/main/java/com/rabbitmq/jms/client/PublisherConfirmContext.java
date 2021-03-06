/* Copyright (c) 2019 Pivotal Software, Inc. All rights reserved. */
package com.rabbitmq.jms.client;

import javax.jms.Message;

/**
 * Information an outbound message being confirmed.
 *
 * @see ConfirmListener
 * @since 1.13.0
 */
public class PublisherConfirmContext {

    private final Message message;
    private final boolean ack;

    PublisherConfirmContext(Message message, boolean ack) {
        this.message = message;
        this.ack = ack;
    }

    /**
     * The message being confirmed.
     *
     * @return the confirmed message
     */
    public Message getMessage() {
        return message;
    }

    /**
     * Whether the message is confirmed or nack-ed (considered lost).
     *
     * @return true if confirmed, false if nack-ed
     */
    public boolean isAck() {
        return ack;
    }
}
