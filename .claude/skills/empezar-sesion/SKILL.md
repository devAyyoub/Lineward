---
description: Arranca la sesión situando fase, tarea y siguiente paso a partir de docs/private/PROYECTO.md.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git log *)
---

## Estado del repositorio

!`git status --short --branch`

## Últimos commits

!`git log --oneline -5`

## Instrucciones

Lee docs/private/PROYECTO.md, secciones 1, 2, 3 y 12.
Dime en qué fase y tarea estamos, cuál es el siguiente paso recomendado y qué piezas del proyecto toca. Nombra la tarea con su clave de Jira, que está en el Anexo C. Si no sabes cuál es la tarea en curso, propón la siguiente por orden de roadmap con su clave.
Ten en cuenta el estado del repositorio de arriba: si hay cambios sin commit, dímelo antes de empezar, y no los mezcles con lo nuevo en un mismo commit. Ese bloque solo mira el repositorio público, así que comprueba además el privado con `git -C docs/private status --short`, que es donde viven CLAUDE.md y PROYECTO.md.
Si hay commits sin publicar, dilo preguntando al servidor con `git ls-remote origin` y no a la referencia local.
Dime en qué rama estoy. El trabajo va en una rama que sale de `develop` (sección 1.4), así que propón su nombre junto con el alcance.
Propón el alcance de la sesión y espera mi confirmación antes de escribir código. Cuando lo confirme, mueve la tarjeta a `En curso` con el conector de Atlassian y dímelo en una línea. Si el conector no está en la sesión, dímelo y la muevo yo (sección 1.2).
