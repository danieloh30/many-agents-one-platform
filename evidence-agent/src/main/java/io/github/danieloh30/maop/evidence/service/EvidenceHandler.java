package io.github.danieloh30.maop.evidence.service;

import io.github.danieloh30.maop.evidence.agentic.EvidenceAgent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.danieloh30.maop.common.BudgetTracker;
import io.github.danieloh30.maop.common.Deadlines;
import io.github.danieloh30.maop.common.RunContext;
import io.github.danieloh30.maop.common.TaskFailure;
import io.github.danieloh30.maop.common.model.EvidenceCompleted;
import io.github.danieloh30.maop.common.model.EvidenceItem;
import io.github.danieloh30.maop.common.model.EvidenceTaskCommand;
import io.github.danieloh30.maop.common.model.FaultInjection;
import io.github.danieloh30.maop.common.model.TaskKind;
import io.github.danieloh30.maop.common.events.EventTypes;
import io.github.danieloh30.maop.common.messaging.Disposition;
import io.github.danieloh30.maop.common.messaging.InboundMessage;
import io.github.danieloh30.maop.common.messaging.MessageHandler;
import io.github.danieloh30.maop.common.messaging.WorkerSupport;
import io.github.danieloh30.maop.common.agentic.ModelGateway;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/** Consumes task.evidence.requested from the shared durable consumer "evidence-agents". */
@ApplicationScoped
public class EvidenceHandler implements MessageHandler {

    private static final Logger LOG = Logger.getLogger(EvidenceHandler.class);

    @Inject
    EvidenceAgent agent;
    @Inject
    WorkerSupport worker;
    @Inject
    ModelGateway model;
    @Inject
    MeterRegistry registry;

    @Override
    public Disposition handle(InboundMessage m) throws Exception {
        if (!EventTypes.EVIDENCE_REQUESTED.equals(m.event().typeSuffix())) {
            LOG.warnf("Unexpected command type %s on evidence queue; acknowledging", m.event().type());
            return Disposition.ACK;
        }
        EvidenceTaskCommand cmd = m.event().dataAs(EvidenceTaskCommand.class);
        worker.started(m.event(), TaskKind.EVIDENCE, m.delivery());
        Span.current().setAttribute("maop.evidence.topic", cmd.topic().name());
        BudgetTracker budget = new BudgetTracker(cmd.budget());
        try {
            if (Deadlines.passed(cmd.deadline())) {
                throw TaskFailure.deadline("evidence gathering");
            }
            Map<String, ObjectNode> baseline = agent.baseline(cmd, budget);
            if (cmd.faultInjection() == FaultInjection.DROP_ACK_ONCE && m.delivery() == 1) {
                // Explicit, labelled fault injection: the tool calls above are committed, then this delivery is abandoned.
                LOG.infof("Fault injection DROP_ACK_ONCE: abandoning delivery 1 of task %s after committed tool calls", cmd.taskId());
                Span.current().setAttribute("maop.fault_injection", "DROP_ACK_ONCE");
                return new Disposition.Abandon("DROP_ACK_ONCE");
            }
            EvidenceAgent.Gathered g = agent.gather(cmd, baseline, budget);
            EvidenceItem item = new EvidenceItem(g.item().evidenceId(), g.item().taskId(), g.item().topic(),
                    g.item().finding(), g.item().confidence(), g.item().facts(), g.item().dataOrigin(), model.info());
            worker.result(EventTypes.EVIDENCE_COMPLETED, cmd.runId(), cmd.taskId(),
                    new EvidenceCompleted(cmd.taskId(), item, budget.usage(), m.delivery()));
            Counter.builder("maop.tasks").tag("kind", "EVIDENCE").tag("result", "completed").register(registry).increment();
            return Disposition.ACK;
        } catch (TaskFailure f) {
            if (f.retryable()) {
                throw f;
            }
            worker.failed(cmd.runId(), cmd.taskId(), TaskKind.EVIDENCE, f, m.delivery(), budget.usage());
            return Disposition.ACK;
        } finally {
            RunContext.clear();
        }
    }

    @Override
    public void onFinalFailure(InboundMessage m, Exception failure) throws Exception {
        worker.deadLetter(m, TaskKind.EVIDENCE, failure);
    }
}
