/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.ballerinalang.observe.trace;

import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import org.ballerinalang.config.ConfigRegistry;
import org.ballerinalang.observe.trace.config.ConfigLoader;
import org.ballerinalang.observe.trace.config.OpenTracingConfig;
import org.ballerinalang.util.tracer.TraceManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.ballerinalang.observe.trace.Constants.DISABLE_OBSERVE_KEY;
import static org.ballerinalang.util.tracer.TraceConstants.TRACE_PREFIX;

/**
 * This is the class which holds the tracers that are enabled,
 * and bridges all tracers with instrumented code. This also helps
 * decouple tracing from ballerina core.
 * Note: This class will be loaded by {@code TraceManagerWrapper}.
 */
public class OpenTracerManager implements TraceManager {
    private TracersStore tracerStore;
    private boolean enabled;

    public OpenTracerManager() {
        OpenTracingConfig openTracingConfig = ConfigLoader.load();
        if (openTracingConfig != null) {
            enabled = !Boolean.valueOf(ConfigRegistry.getInstance().getConfiguration(DISABLE_OBSERVE_KEY));
            tracerStore = enabled ? new TracersStore(openTracingConfig) : null;
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Map<String, Object> extract(Map<String, String> headers, String serviceName) {
        Map<String, Object> spanContext = new HashMap<>();
        Map<String, Tracer> tracers = tracerStore.getTracers(serviceName);
        for (Map.Entry<String, Tracer> tracerEntry : tracers.entrySet()) {
            spanContext.put(tracerEntry.getKey(), tracerEntry.getValue().extract(Format.Builtin.HTTP_HEADERS,
                    new RequestExtractor(headers)));
        }
        return spanContext;
    }

    @Override
    public Map<String, String> inject(Map<String, ?> activeSpanMap, String serviceName) {
        Map<String, String> carrierMap = new HashMap<>();
        for (Map.Entry<String, ?> activeSpanEntry : activeSpanMap.entrySet()) {
            Map<String, Tracer> tracers = tracerStore.getTracers(serviceName);
            Tracer tracer = tracers.get(activeSpanEntry.getKey());
            if (tracer != null) {
                Span span = (Span) activeSpanEntry.getValue();
                if (span != null) {
                    tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new RequestInjector(TRACE_PREFIX,
                            carrierMap));
                }
            }
        }
        return carrierMap;
    }

    @Override
    public Map<String, Object> startSpan(String spanName, Map<String, ?> spanContextMap,
                                         Map<String, String> tags, String serviceName) {
        Map<String, Object> spanMap = new HashMap<>();
        Map<String, Tracer> tracers = tracerStore.getTracers(serviceName);
        for (Map.Entry spanContextEntry : spanContextMap.entrySet()) {
            Tracer tracer = tracers.get(spanContextEntry.getKey().toString());
            Tracer.SpanBuilder spanBuilder = tracer.buildSpan(spanName);

            for (Map.Entry<String, String> tag : tags.entrySet()) {
                spanBuilder = spanBuilder.withTag(tag.getKey(), tag.getValue());
            }

            if (spanContextEntry.getValue() != null) {
                spanBuilder = spanBuilder.asChildOf((SpanContext) spanContextEntry.getValue());
            }

            Span span = spanBuilder.start();
            spanMap.put(spanContextEntry.getKey().toString(), span);
        }
        return spanMap;
    }

    @Override
    public void finishSpan(List<?> spans) {
        for (Object spanObj : spans) {
            Span span = (Span) spanObj;
            span.finish();
        }
    }

    @Override
    public void log(List<?> spans, Map<String, ?> fields) {
        for (Object spanObj : spans) {
            Span span = (Span) spanObj;
            span.log(fields);
        }
    }

    @Override
    public void addTags(List<?> spanList, Map<String, String> tags) {
        for (Object spanObj : spanList) {
            Span span = (Span) spanObj;
            for (Map.Entry<String, String> tag : tags.entrySet()) {
                span.setTag(tag.getKey(), String.valueOf(tag.getValue()));
            }
        }
    }
}
