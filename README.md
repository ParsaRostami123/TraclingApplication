# App Usage Tracker

Una aplicación Flutter que muestra todas las aplicaciones instaladas en un dispositivo Android y el tiempo que el usuario ha pasado en cada una de ellas durante el día actual.

## Características

- Muestra todas las aplicaciones instaladas en el dispositivo con sus iconos
- Rastrea y muestra el tiempo de uso de cada aplicación en el día actual
- Representación visual del tiempo de uso con barras de progreso codificadas por colores
- Experiencia de usuario intuitiva con interfaz moderna y atractiva

## Requisitos

- Android 5.0 (API nivel 21) o superior
- Permisos de "Acceso a Uso" habilitados en el dispositivo

## Limitaciones

- **Solo funciona en Android**: Esta aplicación utiliza APIs específicas de Android para acceder a las estadísticas de uso, por lo que no funcionará en iOS.
- **Requiere permisos especiales**: El usuario debe habilitar manualmente los permisos de "Acceso a Uso" en la configuración del sistema Android.
- **Solo muestra estadísticas del día actual**: La implementación actual solo muestra el tiempo de uso para el día en curso.

## Instalación

1. Clona este repositorio
2. Ejecuta `flutter pub get` para instalar las dependencias
3. Conecta un dispositivo Android o inicia un emulador
4. Ejecuta `flutter run` para instalar y ejecutar la aplicación

## Cómo usar la aplicación

1. Al iniciar la aplicación por primera vez, se te pedirá que habilites los permisos necesarios
2. Toca "Grant Permission" y sigue las instrucciones para habilitar el "Acceso a Uso" para la aplicación
3. Regresa a la aplicación después de habilitar los permisos y verás una lista de todas las aplicaciones instaladas
4. Cada aplicación mostrará cuánto tiempo la has utilizado hoy
5. Puedes utilizar el botón de actualización en la esquina superior derecha para refrescar las estadísticas

## Permisos requeridos

- `QUERY_ALL_PACKAGES`: Para listar todas las aplicaciones instaladas
- `PACKAGE_USAGE_STATS`: Para acceder a las estadísticas de uso de aplicaciones

## Tecnologías utilizadas

- Flutter
- dart:async para manejo de operaciones asíncronas
- device_apps: Para listar las aplicaciones instaladas
- app_usage: Para acceder a las estadísticas de uso
- permission_handler: Para gestionar permisos
- Method Channels para interactuar con código nativo de Android

## Desarrollo futuro

Posibles mejoras para implementar en el futuro:

- Estadísticas para períodos personalizados (semanal, mensual)
- Gráficos detallados del tiempo de uso
- Establecer límites de tiempo para aplicaciones
- Notificaciones cuando se exceden los límites de tiempo
- Categorización de aplicaciones
