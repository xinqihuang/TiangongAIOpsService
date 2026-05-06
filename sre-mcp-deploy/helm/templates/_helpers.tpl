{{/*
Expand the name of the chart.
*/}}
{{- define "sre-mcp.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "sre-mcp.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart label.
*/}}
{{- define "sre-mcp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels applied to all resources.
*/}}
{{- define "sre-mcp.labels" -}}
helm.sh/chart: {{ include "sre-mcp.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}

{{/*
Selector labels for a given module (rca / monitor / remediation).
Usage: {{ include "sre-mcp.selectorLabels" (dict "module" "rca" "context" .) }}
*/}}
{{- define "sre-mcp.selectorLabels" -}}
app.kubernetes.io/name: {{ printf "sre-mcp-%s" .module }}
app.kubernetes.io/component: {{ .module }}
app.kubernetes.io/part-of: sre-mcp-platform
{{- end }}

{{/*
ServiceAccount name for a module.
*/}}
{{- define "sre-mcp.serviceAccountName" -}}
{{- printf "sre-mcp-%s" .module }}
{{- end }}

{{/*
Image reference for a module.
Usage: {{ include "sre-mcp.image" (dict "module" "rca" "context" .) }}
*/}}
{{- define "sre-mcp.image" -}}
{{- $mod := index .context.Values .module -}}
{{- printf "%s:%s" $mod.image.repository $mod.image.tag }}
{{- end }}
