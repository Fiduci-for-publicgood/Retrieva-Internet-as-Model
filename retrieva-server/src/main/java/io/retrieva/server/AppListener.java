package io.retrieva.server;

import io.retrieva.arrow.ArrowMemoryRepository;
import io.retrieva.core.Agent;
import io.retrieva.core.Memory;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.annotation.WebListener;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires the service at startup and persists memory on a timer and at shutdown. Any configuration problem throws
 * from {@link #contextInitialized}, which fails the deployment instead of serving with a weaker setup.
 */
@WebListener
public final class AppListener implements ServletContextListener {
    private static final Logger LOG = Logger.getLogger(AppListener.class.getName());

    private Agent agent;
    private Memory memory;
    private ArrowMemoryRepository repo;
    private ScheduledExecutorService saver;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext ctx = event.getServletContext();
        AppConfig cfg = AppConfig.from(k -> {
            String v = System.getenv(k);
            return v != null ? v : ctx.getInitParameter(k);
        });
        try {
            SourceFactory.Built built = SourceFactory.build(cfg);
            repo = new ArrowMemoryRepository(cfg.memoryDir(), Memory.TRIPLE_CAP, Memory.OUTLINE_CAP, 3, () -> System.currentTimeMillis() / 1000.0);
            ArrowMemoryRepository.Loaded loaded = repo.load();
            loaded.warnings().forEach(w -> LOG.warning("memory: " + w));
            memory = loaded.memory();
            LOG.info("memory loaded: " + memory.store.size() + " triples, " + memory.outline.routeCount() + " routes, sources " + built.sources().size());
            agent = new Agent(built.sources(), built.gate(), Agent.Config.defaults().withBudget(cfg.quickBudgetSeconds()).withMaxCrawlers(cfg.maxCrawlers()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot start: " + e.getMessage(), e);
        }
        ApiHandler handler = new ApiHandler(agent, memory, cfg, new Metrics());
        ServletRegistration.Dynamic reg = ctx.addServlet("retrieva-api", new ApiServlet(handler, cfg.maxLongBody()));
        reg.addMapping("/api/*");
        saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "retrieva-persist");
            t.setDaemon(true);
            return t;
        });
        saver.scheduleWithFixedDelay(this::persist, cfg.persistSeconds(), cfg.persistSeconds(), TimeUnit.SECONDS);
    }

    private void persist() {
        try {
            if (memory != null && memory.dirty()) repo.save(memory);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.SEVERE, "persisting memory failed; will retry", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (saver != null) {
            saver.shutdown();
            try {
                saver.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        persist();
        if (agent != null) agent.close();
    }
}
