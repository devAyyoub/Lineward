---
description: Cierra la tarea actual: resumen, verificación, actualización del documento y los commits de los dos repositorios.
disable-model-invocation: true
---

Vamos a cerrar la tarea actual siguiendo el protocolo de la sección 1.2 de docs/private/PROYECTO.md:

1. Resume lo hecho en lenguaje claro: qué se hizo, por qué así y qué conviene revisar.
2. Dime qué se verificó **ejecutándolo**, no solo qué tests pasan, y qué quedó sin verificar.
3. Enumera las decisiones que tomaste tú y que yo no he aprobado todavía.
4. Escribe la entrada del registro de sesiones (sección 12.3) y actualiza el estado (12.1 y la checklist de la fase).
5. Propón un ADR si hubo decisión, y la pasada completa de la sección 14: una entrada por cada fallo, sorpresa o fuente poco fiable de la sesión.
6. Haz la **pasada de afirmaciones de la sección 1.8**, y hazla mirando, no de memoria. Enumera lo que has afirmado durante la sesión de estos tres tipos, y para cada cosa di dónde lo comprobaste: que algo está decidido, pendiente, construido o ausente; una propiedad del código nuevo, como que es idempotente o que algo no se puede perder; y que una herramienta ha fallado. Lo que no tenga fuente, compruébalo antes de cerrar o corrige la frase, en el código y en el documento. Si no afirmaste nada de los tres tipos, dilo explícitamente en lugar de saltarte el punto.
7. Recuérdame los commits de **los dos repositorios**: el público en la raíz y el privado en `docs/private/`. Un `git add -A` en la raíz no incluye el privado, porque está ignorado, así que el documento de trabajo se queda sin subir si nadie lo dice.

Muéstrame los cambios del documento antes de escribirlos.
