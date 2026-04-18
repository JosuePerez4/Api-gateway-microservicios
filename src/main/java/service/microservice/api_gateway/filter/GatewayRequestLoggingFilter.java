package service.microservice.api_gateway.filter;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

@Component
public class GatewayRequestLoggingFilter implements GlobalFilter, Ordered {

	private static final Logger log = LoggerFactory.getLogger(GatewayRequestLoggingFilter.class);

	public static final String REQUEST_ID_HEADER = "X-Request-Id";

	private static final String START_TIME_NANOS_ATTR = GatewayRequestLoggingFilter.class.getName() + ".startTimeNanos";

	private static final String TIMER_SAMPLE_ATTR = GatewayRequestLoggingFilter.class.getName() + ".timerSample";

	private final MeterRegistry meterRegistry;

	public GatewayRequestLoggingFilter(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String requestId = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER))
			.filter(s -> !s.isBlank())
			.orElseGet(() -> UUID.randomUUID().toString());

		ServerHttpRequest requestWithId = exchange.getRequest().mutate().header(REQUEST_ID_HEADER, requestId).build();
		ServerWebExchange decorated = exchange.mutate().request(requestWithId).build();
		decorated.getAttributes().put(START_TIME_NANOS_ATTR, System.nanoTime());
		decorated.getAttributes().put(TIMER_SAMPLE_ATTR, Timer.start(meterRegistry));

		return chain.filter(decorated).doFinally(signalType -> {
			Long startNanos = decorated.getAttribute(START_TIME_NANOS_ATTR);
			long durationMs = startNanos == null ? -1L : (System.nanoTime() - startNanos) / 1_000_000L;

			Route route = decorated.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
			String routeId = route != null ? route.getId() : "unmatched";

			HttpMethod method = decorated.getRequest().getMethod();
			String path = decorated.getRequest().getURI().getPath();

			HttpStatusCode status = decorated.getResponse().getStatusCode();
			int statusCode = status != null ? status.value() : 0;
			String statusTag = statusCode > 0 ? String.valueOf(statusCode) : "UNKNOWN";
			String outcomeTag = outcomeTag(signalType);

			Timer.Sample sample = decorated.getAttribute(TIMER_SAMPLE_ATTR);
			if (sample != null) {
				sample.stop(Timer.builder("gateway.route.latency")
					.description("Latencia de peticiones enrutadas por el API Gateway")
					.tag("route.id", routeId)
					.tag("http.status", statusTag)
					.tag("outcome", outcomeTag)
					.register(meterRegistry));
			}

			meterRegistry.counter("gateway.route.requests", "route.id", routeId, "http.status", statusTag, "outcome", outcomeTag)
				.increment();

			log.info(
					"gateway request requestId={} method={} path={} routeId={} status={} durationMs={} signal={}",
					requestId,
					method != null ? method.name() : "",
					path,
					routeId,
					statusCode,
					durationMs,
					signalType);
		});
	}

	private static String outcomeTag(SignalType signalType) {
		if (signalType == null) {
			return "unknown";
		}
		return switch (signalType) {
			case ON_COMPLETE -> "complete";
			case ON_ERROR -> "error";
			case CANCEL -> "cancel";
			default -> signalType.name().toLowerCase();
		};
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

}
