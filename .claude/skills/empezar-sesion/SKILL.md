---
description: Arranca la sesión situando fase, tarea y siguiente paso a partir de docs/PROYECTO.md, y pregunta el modo de trabajo.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git log *)
---

## Estado del repositorio

!`git status --short`

## Últimos commits

!`git log --oneline -5`

## Instrucciones

Lee docs/PROYECTO.md, secciones 1, 2, 3 y 12.
Dime en qué fase y tarea estamos, cuál es el siguiente paso recomendado y qué conceptos de IAM y de ingeniería vamos a tocar.
Ten en cuenta el estado del repositorio de arriba: si hay cambios sin commit, dímelo.
Pregúntame qué modo de trabajo quiero (Guía, Pareja o Delegado) antes de empezar.
No escribas código todavía.
