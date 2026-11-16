# Authorization for the protected tool service (docs/security.md section 4).
# OPA decides; tool-service enforces. Input is assembled by tool-service from trusted sources only:
# the caller's SPIFFE ID comes from the verified mTLS peer certificate, approvals from its own database.
package maop.tools

import rego.v1

default allow := false

# Workload names (the ServiceAccount path segment of the SPIFFE ID) for callers in our trust domain.
caller_name := name if {
	parts := split(input.caller.spiffe_id, "/")
	count(parts) == 7 # ["spiffe:", "", <td>, "ns", <ns>, "sa", <name>]
	parts[0] == "spiffe:"
	parts[3] == "ns"
	parts[5] == "sa"
	name := parts[6]
}

trusted_domain if input.caller.trust_domain == data.maop.trust_domain

trusted_domain if {
	data.maop.trust_domain == ""
	input.caller.trust_domain != ""
}

permissions := data.maop.callers[caller_name]

# --- read tools -------------------------------------------------------------------------
allow if {
	trusted_domain
	input.action == "tool.invoke"
	input.tool.class == "READ"
	"tool.read" in permissions
}

# --- simulated remediation: requires a matching, unexpired human approval ----------------
allow if {
	trusted_domain
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	"tool.remediate" in permissions
	input.tool.simulated == true
	approval_valid
}

approval_valid if {
	input.approval != null
	input.approval.run_id == input.run.id
	input.approval.action_digest == input.request.action_digest
	time.parse_rfc3339_ns(input.approval.expires_at) > time.parse_rfc3339_ns(input.now)
}

# --- non-tool actions --------------------------------------------------------------------
allow if {
	trusted_domain
	input.action in {"approval.register", "audit.read", "admin.reset", "tools.list"}
	input.action in permissions
}

# --- reasons (explain every deny; empty when allowed) -------------------------------------
reasons contains "untrusted_domain" if not trusted_domain

reasons contains "caller_not_permitted" if {
	trusted_domain
	not caller_permitted
}

caller_permitted if {
	input.action == "tool.invoke"
	input.tool.class == "READ"
	"tool.read" in permissions
}

caller_permitted if {
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	"tool.remediate" in permissions
}

caller_permitted if {
	input.action != "tool.invoke"
	input.action in permissions
}

reasons contains "not_simulated" if {
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	input.tool.simulated != true
}

reasons contains "approval_required" if {
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	caller_permitted
	input.approval == null
}

reasons contains "approval_mismatch" if {
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	input.approval != null
	not approval_matches
}

approval_matches if {
	input.approval.run_id == input.run.id
	input.approval.action_digest == input.request.action_digest
}

reasons contains "approval_expired" if {
	input.action == "tool.invoke"
	input.tool.class == "REMEDIATION"
	approval_matches
	time.parse_rfc3339_ns(input.approval.expires_at) <= time.parse_rfc3339_ns(input.now)
}

decision := {
	"allow": allow,
	"reasons": sort([r | some r in reasons]),
	"revision": data.maop.policy_revision,
}
