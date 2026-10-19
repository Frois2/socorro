# Socorro

Aplicativo Android acadêmico em Java, com componentes nativos e interface em português. Reúne detecção de queda, SOS, contatos de emergência, check-in, clima e histórico de trajetos.

## Executar

Abra esta pasta no Android Studio. Use Java 21, SDK Android 36 e Gradle 8.13. O aplicativo atende Android 8 ou superior. A configuração usa os arquivos Groovy `.gradle`. O APK para instalação fica em `output/apk/Socorro.apk`.

1. Em **Ajustes**, salve seu nome e cadastre os contatos com DDD.
2. Em **Enviar alertas por**, escolha **SMS automático**, **WhatsApp · confirmar envio** ou **SMS + WhatsApp**. Para SMS, ative **Autorizar SMS automático** e conceda a permissão. O Android pode restringir essa permissão conforme o instalador e a política do aparelho. A interface informa quando ela não foi concedida.
3. Escolha o intervalo de check-in: 15, 30 ou 60 minutos, ou desative-o.
4. No início, permita localização para consultar clima e posição antes de sair.
5. Toque em **Iniciar monitoramento** e conceda as permissões solicitadas. O serviço continua com notificação mesmo ao sair da tela. **Encerrar trajeto** para os sensores e salva o percurso.

O botão SOS fica disponível nas quatro abas. Queda, SOS ou check-in sem resposta usam uma contagem de **30 segundos**. **Estou bem · cancelar alerta** cancela o envio e reinicia o intervalo do check-in. A ação também está na notificação de emergência. Encerrar o monitoramento cancela uma contagem ainda ativa.

## Telas

- **Início:** estado do monitoramento, velocidade, duração, bateria, posição e precisão GPS, prévia do percurso, clima, próximo check-in, contatos e mensagens pendentes do WhatsApp.
- **Sensores:** gráfico real da aceleração, eixos do acelerômetro/giroscópio, proximidade, luminosidade, pressão e temperatura física, quando disponíveis. Ausência de sensor aparece explicitamente.
- **Histórico:** trajetos com distância e duração; eventos e alertas com estados de envio, ID retornado pela API e resultado do SMS por contato. O mapa interativo desenha o trajeto salvo sobre ruas do OpenStreetMap.
- **Ajustes:** nome, até cinco contatos, escolha de SMS/WhatsApp/ambos, autorização de SMS automático, intervalo de check-in, modo noturno pela luminosidade e acesso às permissões do Android.

Em Ajustes → Aparência, escolha Automático por luminosidade, Claro, Escuro (preto) ou Seguir o Android. O automático acompanha a leitura em lux e usa uma faixa interna para evitar alternância constante, sem ajuste manual de limite. A leitura do sensor e o modo escolhido aparecem na tela. Não há espera adicional no aplicativo; a frequência física depende do aparelho. Sem sensor de luz, o automático acompanha o Android. Claro e Escuro independem do sensor. A versão atual é 1.3.3 (código 7).

## Sensores e detecção

A heurística combina impacto de 3 G com rotação acima de 2,5 rad/s nos últimos 1,5 segundos, seguido de pelo menos 1 segundo de repouso (0,7 a 1,3 G) dentro de 5 segundos. Existe um intervalo mínimo de 30 segundos entre detecções. Sem giroscópio, o limite sobe para 3,5 G. Com proximidade não virtual, registrada e com leitura válida indicando objeto afastado, o limite é 4,5 G, exceto se houver queda livre recente ou queda relativa de altitude de pelo menos 0,8 m. Antes da primeira leitura, sem registro ativo ou com dados inválidos, a proximidade é ignorada pelo detector. No Galaxy A34 (SM-A346) e em sensores identificados como virtuais, a indicação é apenas informativa e não altera o limite de queda. O app exibe perto/longe e não afirma reconhecer bolso ou mochila. O barômetro usa uma referência curta, sem apresentar altitude absoluta como medição precisa.

O algoritmo é acadêmico e não tem sensibilidade/especificidade clínica validada. Não provoque quedas reais para testar. O serviço exige localização e mantém a CPU ativa durante o monitoramento para a contagem funcionar com a tela apagada; isso consome bateria. Não reinicia após encerramento forçado do aplicativo/sistema.

## API e sincronização

- `POST https://ideaberta.com.br/socorro/`
- Contrato consultado: https://app.swaggerhub.com/apis/ANTONIOMOREIRANETO/Socorro/1.0.0
- `tipo_registro: 1` para primeira localização e posições a cada minuto, como no fluxo original; `2` para alertas após a contagem.
- Nome, UUID da instalação, modelo, bateria e sensores disponíveis seguem os nomes e unidades do Swagger. Coordenadas e velocidade só entram quando a localização tem menos de 2 minutos. Temperatura do clima não é enviada como se fosse leitura do sensor físico.
- SQLite grava evento e fila na mesma transação, prioriza alertas e só remove o envio após HTTP 200 e `status: sucesso`, como na integração original. ID ausente ou campos adicionais normalizados pelo servidor não provocam reenvio de um registro já aceito. Divergências de nome e tipo são mostradas como aviso para conferência. Falhas permanecem pendentes e são tentadas a cada minuto com o serviço ativo. Ao reiniciar o monitoramento, a fila é retomada. Não há cartão de envio manual no Início ou nos Ajustes. Cada evento guarda uma cópia dos dados para consulta mesmo após a confirmação. A migração preserva a fila da versão anterior.
- A API não oferece chave de idempotência: perda da resposta após a gravação pode causar duplicidade no reenvio.

**Limite do backend fornecido:** o GET é um painel HTML paginado, sem endpoint JSON para recuperar trajetos, contatos ou históricos por dispositivo. Por isso, o app sincroniza as posições periódicas e os alertas aceitos pela API, mostra a confirmação de cada registro e abre o painel. O percurso detalhado (pontos com precisão até 100 m, amostrados conforme movimento/tempo) e seus agrupamentos permanecem no aparelho. Não é possível restaurar esse percurso após desinstalar usando apenas o contrato atual; isso exigiria ampliar o backend. O mapa simplifica visualmente percursos com mais de 1.000 pontos, sem apagar o arquivo de pontos no banco.

## Contatos e clima

O SMS é enviado automaticamente no fim da contagem somente se habilitado e autorizado no Android. Inclui nome, motivo e link da localização recente; sem GPS recente, informa que a localização está indisponível. O SMS usa a operadora do aparelho, requer chip com serviço e pode ser cobrado, inclusive por partes. O histórico distingue solicitação à operadora, aceitação e falha; aceitação não significa entrega ou leitura. O WhatsApp usa clique para conversa, com mensagem e localização preenchidas. Ao terminar a contagem, a opção WhatsApp prepara mensagens para os contatos e mostra notificação e botão de pendências no aplicativo. O usuário precisa escolher cada contato e confirmar Enviar no WhatsApp ou WhatsApp Business. Abrir a conversa não registra entrega; as pendências são limpas explicitamente. Na opção SMS + WhatsApp, o SMS automático continua independente. Não há acionamento automático de ambulância.

Clima: Open-Meteo, sem chave, com temperatura, probabilidade de chuva nas próximas 3 horas, vento e rajadas. A consulta ocorre com localização recente, antes ou durante o monitoramento. Cache de 15 minutos; dados antigos, mudança de região e falhas de conexão são indicados. Os avisos de chuva/vento/neblina são regras do aplicativo sobre os dados da previsão, não alertas oficiais de defesa civil. Fonte: https://open-meteo.com/en/docs

Mapas: componente nativo em Canvas, com projeção Mercator, arraste, zoom e mosaicos OpenStreetMap. A atribuição fica visível; o cache HTTP respeita os cabeçalhos do servidor, com limite de 20 MB, sem download em massa de mapas offline. O traçado GPS permanece visível mesmo se as ruas não carregarem. O app faz requisições aos serviços de clima e mapas com a região consultada.

## Validação

```
.\gradlew.bat assembleDebug lintDebug assembleDebugAndroidTest
javac -d build/detector-test app/src/main/java/br/com/socorro/FallDetector.java app/src/main/java/br/com/socorro/SafetyTimer.java app/src/main/java/br/com/socorro/WeatherRisk.java tests/FallDetectorTest.java
java -cp build/detector-test FallDetectorTest
```

Os testes independentes cobrem detecção, filtros de contexto, repouso, contagem, cancelamento e regras de clima. Os instrumentados cobrem migração da fila, persistência, prioridade de alertas, confirmação de envio, trajetos, resultados de SMS, contatos, expiração do GPS, telas, mapa e SOS/check-in. O transporte HTTP é interceptado nos testes e o SMS fica desativado: os testes não criam registros no servidor da disciplina e não enviam mensagens reais.

Para instrumentação, use apenas um emulador de teste e execute `connectedDebugAndroidTest` (ou instale os dois APKs e invoque `androidx.test.runner.AndroidJUnitRunner`). Capturas são geradas na pasta externa `files/screens` do app no emulador. Os testes alteram preferências e histórico do aplicativo de teste; não os execute sobre dados pessoais.

Em um aparelho físico, ainda é necessário validar a qualidade do detector, GPS, funcionamento com tela apagada e SMS com a operadora escolhida. O APK debug fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Verificações realizadas

Compilação e lint aprovados. A lógica independente passou em 35 verificações. Os testes instrumentados validaram as unidades dos sensores, os modos de aparência e a troca automática de fundo no emulador. A última alteração do tema passou em dois testes instrumentados, incluindo a confirmação de que configurações antigas de limite são ignoradas.
