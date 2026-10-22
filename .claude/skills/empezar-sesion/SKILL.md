---
description: Arranca la sesión situando fase, tarea y siguiente paso a partir de docs/private/PROYECTO.md.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git log *)
---

## Estado del repositorio

!`git status --short`

## Últimos commits

!`git log --oneline -5`

## Instrucciones

Lee docs/private/PROYECTO.md, secciones 1, 2, 3 y 12.
Dime en qué fase y tarea estamos, cuál es el siguiente paso recomendado y qué piezas del proyecto toca.
Ten en cuenta el estado del repositorio de arriba: si hay cambios sin commit, dímelo antes de empezar, y no los mezcles con lo nuevo en un mismo commit.
Si hay commits sin publicar, dilo preguntando al servidor con `git ls-remote origin` y no a la referencia local.
Propón el alcance de la sesión y espera mi confirmación antes de escribir código.
