package com.vedicmeet.appserver.call;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure call state machines to their concrete Mongo/Redis adapter. */
@Configuration
public class CallRuntimeConfiguration {

    @Bean CallOpenService callOpenService(MongoRedisCallRuntimeStore store) {
        return new CallOpenService(store);
    }

    @Bean CallAcceptService callAcceptService(MongoRedisCallRuntimeStore store) {
        return new CallAcceptService(store);
    }

    @Bean CallEndService callEndService(MongoRedisCallRuntimeStore store) {
        return new CallEndService(store);
    }

    @Bean CallCancelService callCancelService(MongoRedisCallRuntimeStore store) {
        return new CallCancelService(store);
    }

    @Bean CallExtendService callExtendService(MongoRedisCallRuntimeStore store) {
        return new CallExtendService(store);
    }

    @Bean CallQueueService callQueueService(MongoRedisCallRuntimeStore store) {
        return new CallQueueService(store);
    }
}
