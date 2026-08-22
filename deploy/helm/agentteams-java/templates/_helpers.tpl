{{- define "agentteams-java.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "agentteams-java.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "agentteams-java.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "agentteams-java.controlPlaneServiceAccountName" -}}
{{- default (printf "%s-control-plane" (include "agentteams-java.fullname" .)) .Values.controlPlane.serviceAccount.name -}}
{{- end -}}
{{- define "agentteams-java.operatorServiceAccountName" -}}
{{- default (printf "%s-operator" (include "agentteams-java.fullname" .)) .Values.operator.serviceAccount.name -}}
{{- end -}}
{{- define "agentteams-java.gatewayServiceAccountName" -}}
{{- default (printf "%s-gateway" (include "agentteams-java.fullname" .)) .Values.gateway.serviceAccount.name -}}
{{- end -}}
