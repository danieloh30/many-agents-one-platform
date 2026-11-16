package policytest.maop_tools

import rego.v1

import data.maop.tools

td := "maop.example"

caller(name) := {"spiffe_id": sprintf("spiffe://%s/ns/maop/sa/%s", [td, name]), "trust_domain": td}

base(name, action, class) := {
	"caller": caller(name),
	"action": action,
	"tool": {"name": "x", "class": class, "simulated": true},
	"run": {"id": "run-1"},
	"request": {"action_digest": "sha256:abc"},
	"approval": null,
	"now": "2026-01-01T12:00:00Z",
}

grant := {"run_id": "run-1", "action_digest": "sha256:abc", "expires_at": "2026-01-01T12:10:00Z"}

callers := {
	"evidence-agent": ["tool.read", "tools.list"],
	"recommendation-agent": ["tool.read", "tool.remediate", "tools.list"],
	"triage-agent": ["approval.register", "tools.list"],
	"ops-console": ["audit.read", "admin.reset", "tools.list"],
}

test_evidence_agent_may_read if {
	d := tools.decision with input as base("evidence-agent", "tool.invoke", "READ") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	d.allow
	d.reasons == []
}

test_evidence_agent_may_not_remediate if {
	d := tools.decision with input as object.union(base("evidence-agent", "tool.invoke", "REMEDIATION"), {"approval": grant})
		with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"caller_not_permitted" in d.reasons
}

test_recommendation_without_approval_denied if {
	d := tools.decision with input as base("recommendation-agent", "tool.invoke", "REMEDIATION") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	d.reasons == ["approval_required"]
}

test_recommendation_with_matching_approval_allowed if {
	d := tools.decision with input as object.union(base("recommendation-agent", "tool.invoke", "REMEDIATION"), {"approval": grant})
		with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	d.allow
}

test_approval_for_other_action_denied if {
	other := object.union(grant, {"action_digest": "sha256:other"})
	d := tools.decision with input as object.union(base("recommendation-agent", "tool.invoke", "REMEDIATION"), {"approval": other})
		with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"approval_mismatch" in d.reasons
}

test_approval_for_other_run_denied if {
	other := object.union(grant, {"run_id": "run-2"})
	d := tools.decision with input as object.union(base("recommendation-agent", "tool.invoke", "REMEDIATION"), {"approval": other})
		with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"approval_mismatch" in d.reasons
}

test_expired_approval_denied if {
	old := object.union(grant, {"expires_at": "2026-01-01T11:59:59Z"})
	d := tools.decision with input as object.union(base("recommendation-agent", "tool.invoke", "REMEDIATION"), {"approval": old})
		with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"approval_expired" in d.reasons
}

test_non_simulated_remediation_denied if {
	i := object.union(base("recommendation-agent", "tool.invoke", "REMEDIATION"), {
		"approval": grant,
		"tool": {"name": "x", "class": "REMEDIATION", "simulated": false},
	})
	d := tools.decision with input as i with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"not_simulated" in d.reasons
}

test_untrusted_domain_denied if {
	i := object.union(base("evidence-agent", "tool.invoke", "READ"), {"caller": {"spiffe_id": "spiffe://evil.example/ns/maop/sa/evidence-agent", "trust_domain": "evil.example"}})
	d := tools.decision with input as i with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"untrusted_domain" in d.reasons
}

test_unknown_workload_denied if {
	d := tools.decision with input as base("intruder", "tool.invoke", "READ") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
	"caller_not_permitted" in d.reasons
}

test_malformed_spiffe_path_denied if {
	i := object.union(base("evidence-agent", "tool.invoke", "READ"), {"caller": {"spiffe_id": sprintf("spiffe://%s/evidence-agent", [td]), "trust_domain": td}})
	d := tools.decision with input as i with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
}

test_only_triage_registers_approvals if {
	tools.decision.allow with input as base("triage-agent", "approval.register", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not tools.decision.allow with input as base("recommendation-agent", "approval.register", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not tools.decision.allow with input as base("evidence-agent", "approval.register", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
}

test_only_console_reads_audit_and_resets if {
	tools.decision.allow with input as base("ops-console", "audit.read", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	tools.decision.allow with input as base("ops-console", "admin.reset", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not tools.decision.allow with input as base("evidence-agent", "audit.read", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not tools.decision.allow with input as base("recommendation-agent", "admin.reset", "") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
}

test_console_cannot_invoke_tools if {
	d := tools.decision with input as base("ops-console", "tool.invoke", "READ") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	not d.allow
}

test_empty_trust_domain_config_accepts_configured_caller_domain if {
	d := tools.decision with input as base("evidence-agent", "tool.invoke", "READ")
		with data.maop.trust_domain as ""
		with data.maop.callers as callers
	d.allow
}

test_decision_carries_revision if {
	d := tools.decision with input as base("evidence-agent", "tool.invoke", "READ") with data.maop.trust_domain as td with data.maop.callers as callers with data.maop.policy_revision as "t"
	d.revision == "t"
}
