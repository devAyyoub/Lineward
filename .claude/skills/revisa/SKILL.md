---
description: Revisa mis cambios como un ingeniero senior de seguridad e IAM. Señala los problemas y los explica, no los corrige.
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *) Bash(git show *)
---

## Estado del repositorio

!`git status --short`

## Cambios sin commit

!`git diff HEAD`

## Instrucciones

Revisa los cambios de arriba como lo haría un ingeniero senior de seguridad e IAM.
Si el diff está vacío, revisa el último commit con `git show HEAD` y dímelo antes de empezar.

Evalúa: corrección, seguridad, casos límite, concurrencia, legibilidad, tests y coherencia con la arquitectura de docs/PROYECTO.md.

No corrijas el código tú: señala los problemas, explica por qué lo son y déjame arreglarlos.
