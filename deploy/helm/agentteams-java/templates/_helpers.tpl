{{- define "agentteams-java.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "agentteams-java.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "agentteams-java.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "agentteams-java.serviceAccountName" -}}
{{- default (include "agentteams-java.fullname" .) .Values.serviceAccount.name -}}
{{- end -}}
