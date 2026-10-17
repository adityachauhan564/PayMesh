{{/* Labels every PayMesh object carries; "component" names the workload (user-service, mysql, ...) */}}
{{- define "paymesh.labels" -}}
app.kubernetes.io/part-of: paymesh
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/component: {{ .name }}
{{- end }}

{{- define "paymesh.selector" -}}
app.kubernetes.io/part-of: paymesh
app.kubernetes.io/component: {{ .name }}
{{- end }}

{{/* Non-root, read-only root filesystem, no privileges: for our own images (they need a writable /tmp only) */}}
{{- define "paymesh.containerSecurity" -}}
securityContext:
  runAsNonRoot: true
  readOnlyRootFilesystem: true
  allowPrivilegeEscalation: false
  capabilities: { drop: [ALL] }
  seccompProfile: { type: RuntimeDefault }
{{- end }}
