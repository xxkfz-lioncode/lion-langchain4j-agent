package com.lion.agent.tools;

import com.lion.agent.common.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模拟「外部天气接口」客户端：演示 Resilience4j 声明式熔断器 {@link CircuitBreaker}。
 * <p>
 * 为什么不直接把 {@code @CircuitBreaker} 加在 {@link WeatherTools} 的 {@code @Tool} 方法上：
 * 1. LangChain4j 是<b>反射扫描 @Tool 注解</b>来注册工具的，而带 AOP 注解的 Bean 会被 Spring
 *    用 CGLIB 代理；代理子类重写的方法不携带父类方法上的注解，存在扫不到工具的风险
 *    （表现为模型不再调用天气工具），因此让工具类保持"纯净"；
 * 2. 熔断器保护的对象应当是"对外部依赖的调用"，工具方法只是模型与业务之间的适配层，
 *    真正会超时/报错的是外部接口，所以把被保护的调用单独成 Bean；
 * 3. 顺带避开 Spring AOP 的经典坑：同类内部自调用不走代理，注解会失效——拆成两个 Bean 后，
 *    调用必然经过代理。
 * <p>
 * 演示方式：
 * <ul>
 *   <li>把 application.yml 的 {@code weather.mock-failure-rate} 设为 1（每次调用必失败），
 *       或直接问模型「fail 城市的天气」（城市名含 fail 即注入故障）；</li>
 *   <li>连续触发 2 次失败 → 熔断器 CLOSED → OPEN，之后 60 秒内所有天气查询直接走降级文案；</li>
 *   <li>60 秒后进入 HALF_OPEN，试探成功则恢复 CLOSED，失败则再次 OPEN。</li>
 * </ul>
 */
@Slf4j
@Service
public class WeatherApiClient {

    /** 熔断器实例名：必须与 application.yml 中 resilience4j.circuitbreaker.instances 的 key 一致 */
    private static final String CIRCUIT_NAME = "weatherApi";

    private static final String[] CONDITIONS = {"晴", "晴转多云", "多云", "阴", "小雨", "阵雨", "中雨", "雷阵雨"};

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 演示用故障注入概率(0~1)：0=不注入故障，1=每次调用必失败 */
    private final double mockFailureRate;

    public WeatherApiClient(CircuitBreakerRegistry circuitBreakerRegistry,
                            @Value("${weather.mock-failure-rate:0}") double mockFailureRate) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.mockFailureRate = mockFailureRate;
    }

    /**
     * 启动时挂事件监听(演示/排查用)。除状态转换外，成功/失败/被拒绝也都打日志：
     * 只要熔断器在工作，每次调用都会有留痕；一条都没有则说明 @CircuitBreaker 没生效(切面未装配)。
     */
    @PostConstruct
    void registerCircuitBreakerListeners() {
        circuitBreakerRegistry.circuitBreaker(CIRCUIT_NAME)
                .getEventPublisher()
                .onSuccess(event -> log.info("[天气] 调用成功"))
                .onError(event -> log.warn("[天气] 调用失败(已计入熔断统计): {}", event.getThrowable().toString()))
                .onCallNotPermitted(event -> log.warn("[天气] 熔断器已 OPEN, 拒绝调用(快速失败, 直接走降级)"))
                .onStateTransition(event -> log.warn("[天气] 熔断器[{}] 状态转换: {} -> {}", CIRCUIT_NAME,
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * 调用外部天气接口查询今日天气(此处用本地随机数模拟，不产生真实网络请求)。
     * <p>
     * 熔断规则：方法体抛出的 {@link BusinessException} 被计入失败(见 yml 的 record-exceptions)；
     * 失败率超过阈值后熔断器 OPEN，后续调用<b>不再进入本方法体</b>，直接执行降级方法，实现快速失败。
     */
    @CircuitBreaker(name = CIRCUIT_NAME, fallbackMethod = "queryTodayWeatherFallback")
    public String queryTodayWeather(String city) {
        // ↓↓↓ 演示用故障注入：真实项目里这里是一次 HTTP/RPC 调用，可能超时、限流或报错
        if (city.toLowerCase().contains("fail")
                || ThreadLocalRandom.current().nextDouble() < mockFailureRate) {
            throw new BusinessException("模拟外部天气接口异常: 连接超时(city=" + city + ")");
        }

        Random random = new Random(city.toLowerCase().hashCode());
        String condition = CONDITIONS[random.nextInt(CONDITIONS.length)];
        int min = 15 + random.nextInt(10);
        int max = min + 6 + random.nextInt(8);
        int current = min + random.nextInt(max - min + 1);
        int humidity = 40 + random.nextInt(55);
        int wind = 5 + random.nextInt(25);

        return String.format("%s今日天气: %s, 气温 %d~%d°C, 当前 %d°C, 湿度 %d%%, 风力 %dkm/h(模拟数据, 仅供参考)。",
                city, condition, min, max, current, humidity, wind);
    }

    /**
     * 降级方法：方法体抛异常、或熔断器 OPEN 拒绝调用(half-open 试探失败)时执行，用兜底文案替代真实结果。
     * <p>
     * 签名要求：与原方法入参一致，末尾可多一个 {@link Throwable}（用于区分业务异常与熔断拒绝）。
     */
    public String queryTodayWeatherFallback(String city, Throwable t) {
        // 用 var 是为了避开同名冲突: CircuitBreaker(接口) 与 CircuitBreaker(注解) 简单名相同
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker(CIRCUIT_NAME);
        var metrics = circuitBreaker.getMetrics();
        log.warn("[天气] 触发熔断降级: city={}, 状态={}, 失败率={}%, 已统计调用={}次(其中失败={}次), 原因={}",
                city, circuitBreaker.getState(), metrics.getFailureRate(),
                metrics.getNumberOfBufferedCalls(), metrics.getNumberOfFailedCalls(), t.toString());
        return "天气服务暂时不可用(已熔断降级), 请稍后再试。";
    }
}
