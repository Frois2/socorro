# Socorro

Aplicativo Android acadêmico em Java, com componentes nativos e interface em português. Reúne detecção de queda, SOS, contatos de emergência, check-in, clima e histórico de trajetos.

## Executar

Abra esta pasta no Android Studio. Use Java 21, SDK Android 36 e Gradle 8.13. O aplicativo atende Android 8 ou superior. A configuração ativa usa os arquivos Groovy `.gradle`; os arquivos `.kts` e a atividade Kotlin do modelo anterior não fazem parte da compilação.

1. Em **Ajustes**, salve seu nome e cadastre os contatos com DDD.
2. Em **Enviar alertas por**, escolha **SMS automático**, **WhatsApp · confirmar envio** ou **SMS + WhatsApp**. Para SMS, ative **Autorizar SMS automático** e conceda a permissão. O Android pode restringir essa permissão conforme o instalador e a política do aparelho. A interface informa quando ela não foi concedida.
3. Escolha o intervalo de check-in: 15, 30 ou 60 minutos, ou desative-o.
4. No início, permita localização para consultar clima e posição antes de sair.
5. Toque em **Iniciar monitoramento** e conceda as permissões solicitadas. O serviço continua com notificação mesmo ao sair da tela. **Encerrar trajeto** para os sensores e salva o percurso.

O botão SOS fica disponível nas quatro abas. Queda, SOS ou check-in sem resposta usam uma contagem de **30 segundos**. **Estou bem · cancelar alerta** cancela o envio e reinicia o intervalo do check-in. A ação também está na notificação de emergência. Encerrar o monitoramento cancela uma contagem ainda ativa.

## Telas

- **Início:** estado do monitoramento, velocidade, duração, bateria, posição e precisão GPS, prévia do percurso, clima, próximo check-in, contatos e envios pendentes.
- **Sensores:** gráfico real da aceleração, eixos do acelerômetro/giroscópio, proximidade, luminosidade, pressão e temperatura física, quando disponíveis. Ausência de sensor aparece explicitamente.
- **Histórico:** trajetos com distância e duração; eventos e alertas com estados de envio, ID retornado pela API e resultado do SMS por contato. O mapa interativo desenha o trajeto salvo sobre ruas do OpenStreetMap.
- **Ajustes:** nome, até cinco contatos, escolha de SMS/WhatsApp/ambos, autorização de SMS automático, intervalo de check-in, modo noturno pela luminosidade e acesso às permissões do Android.

Em Ajustes → Aparência, escolha Automático por luminosidade, Claro, Escuro (preto) ou Seguir o Android. O automático usa 60 lux como limite inicial, configurável de 10 a 500 lux, com margem de 10 lux para evitar alternância constante. A leitura do sensor e o modo escolhido aparecem na tela. Não há espera adicional no aplicativo; a frequência física depende do aparelho. Sem sensor de luz, o automático acompanha o Android. Claro e Escuro independem do sensor. A versão 1.2 (código 3) aparece no cabeçalho de Ajustes.

## Sensores e detecção

A heurística combina impacto de 3 G com rotação acima de 2,5 rad/s nos últimos 1,5 segundos, seguido de pelo menos 1 segundo de repouso (0,7 a 1,3 G) dentro de 5 segundos. Existe um intervalo mínimo de 30 segundos entre detecções. Sem giroscópio, o limite sobe para 3,5 G. Com proximidade não virtual, registrada e com leitura válida indicando objeto afastado, o limite é 4,5 G, exceto se houver queda livre recente ou queda relativa de altitude de pelo menos 0,8 m. Antes da primeira leitura, sem registro ativo ou com dados inválidos, a proximidade é ignorada pelo detector. No Galaxy A34 (SM-A346) e em sensores identificados como virtuais, a indicação é apenas informativa e não altera o limite de queda. O app exibe perto/longe e não afirma reconhecer bolso ou mochila. O barômetro usa uma referência curta, sem apresentar altitude absoluta como medição precisa.

O algoritmo é acadêmico e não tem sensibilidade/especificidade clínica validada. Não provoque quedas reais para testar. O serviço exige localização e mantém a CPU ativa durante o monitoramento para a contagem funcionar com a tela apagada; isso consome bateria. Não reinicia após encerramento forçado do aplicativo/sistema.

## API e sincronização

- `POST https://ideaberta.com.br/socorro/`
- Contrato consultado: https://app.swaggerhub.com/apis/ANTONIOMOREIRANETO/Socorro/1.0.0
- `tipo_registro: 1` para primeira localização e posições a cada minuto, como no fluxo original; `2` para alertas após a contagem.
- Nome, UUID da instalação, modelo, bateria e sensores disponíveis seguem os nomes e unidades do Swagger. Coordenadas e velocidade só entram quando a localização tem menos de 2 minutos. Temperatura do clima não é enviada como se fosse leitura do sensor físico.
- SQLite grava evento e fila na mesma transação, prioriza alertas e só remove o envio após HTTP 200 e `status: sucesso`, como na integração original. ID ausente ou campos adicionais normalizados pelo servidor não provocam reenvio de um registro já aceito. Divergências de nome e tipo são mostradas como aviso para conferência. Falhas permanecem pendentes e são tentadas a cada minuto com o serviço ativo. Ao reiniciar o monitoramento, a fila é retomada. O botão Enviar agora envia um registro normal e tenta as pendências sem iniciar monitoramento. O nome em edição é salvo antes desse envio; cada evento guarda uma cópia dos dados para consulta mesmo após a confirmação. A migração preserva a fila da versão anterior.
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

### Resultado da validação anterior

- Compilação do app e do APK de testes: aprovada.
- Lint Android: 0 erros (avisos não bloqueantes no relatório de build).
- Lógica independente: 35 verificações aprovadas.
- Android 36.1, tela 720 x 1280: seis casos instrumentados aprovados; cinco no primeiro lote e o caso de interface/mapa no reteste após corrigir sua inicialização. O reteste também validou o cadastro de contato no tema noturno.
- Evidências locais: `build/android-tests.txt` preserva o lote inicial e a falha encontrada; `build/android-ui-test.txt` registra o reteste aprovado. As capturas finais ficam em `build/ui-final/`.
- Não foram enviados SMS reais nem registros de teste ao servidor da disciplina.


## Validação das alterações de tema e canais

Compilação debug e lint aprovados, com zero erros e 19 avisos. As 35 verificações independentes passaram. No emulador Small_Phone, passaram os três testes de interface/mapa, troca imediata de luminosidade com preservação de edição e preparação de WhatsApp sem SMS. As capturas foram conferidas em `build/ui-alertas/`. Nenhuma mensagem real foi enviada. O comportamento físico do sensor de luz e o envio real com operadora/WhatsApp precisam ser conferidos no aparelho.

## Proximidade e segundo plano

O serviço seleciona proximidade wake-up quando disponível e verifica o registro de cada sensor. A tela de sensores usa o estado real do serviço durante o monitoramento. O wake lock é adquirido antes do registro dos sensores e renovado somente durante o trajeto. A notificação oferece Encerrar monitoramento, inclusive fora do app; o serviço não é encerrado apenas por remover a atividade da lista de recentes. Ajustes mostra se o relógio do serviço e o acelerômetro estão atualizando, o estado da otimização de bateria e um atalho aos ajustes de bateria. Restrições do sistema/Samsung, reinicialização e Forçar parada ainda podem interromper o monitoramento; nesses casos, abra o app e inicie novamente. A aplicação não se inicia sozinha após ser encerrada.

Validação de proximidade e segundo plano: compilação e lint aprovados (zero erros, 19 avisos), 35 verificações independentes aprovadas e três testes instrumentados aprovados (40,842 segundos): validação de leituras de proximidade, acelerômetro e check-in com tela apagada/atividade em segundo plano e parada pela notificação, além de regressão das telas e mapa. O HTTP foi interceptado e SMS permaneceu desativado. Esses testes não substituem uma sessão no Galaxy A34 com a configuração de bateria escolhida.

Validação da luminosidade na versão 1.2: no Medium_Phone com sensor habilitado, o Android entregou eventos de 5 e 300 lux e o teste confirmou as duas cores do fundo. Os testes do seletor de quatro modos e da preservação de edição também passaram. O Small_Phone estava com o sensor de luz desabilitado e não serve para validar esse caminho de ponta a ponta. Capturas em `build/ui-luminosidade/`.

## Versão 1.3: nome, envio e retorno da API

O início foi reorganizado com saudação pelo nome, resumo do monitoramento e cartão de dados da API. Início e Ajustes mostram o nome a enviar, quais dados estão disponíveis, pendências, último nome confirmado, ID e horário. Salvar nome no aparelho não é apresentado como confirmação de recebimento. Enviar agora pode ser usado sem iniciar trajeto. A sincronização usa uma fila de execução única para evitar envios simultâneos do mesmo registro. O histórico permite conferir os dados efetivamente enviados.

O teste real no Android em 19/10/2026 não obteve confirmação: o POST HTTPS falhou na negociação TLS. Nos testes da rede local, o endereço HTTP alternativo do Swagger retornou uma página de verificação de segurança no GET e HTTP 405 no POST. O app mantém o endpoint HTTPS documentado, valida os retornos e preserva a fila. A correção do endpoint ou da proteção do servidor depende do responsável pela API; os testes com respostas simuladas não demonstram entrega no servidor real. Consulte `docs/diagnostico-api.md`.

Validação da versão 1.3: compilação e lint aprovados, oito testes instrumentados aprovados em 51,943 segundos, com HTTP interceptado. Foram verificados nome e unidades, envio manual sem monitoramento, rejeição de respostas inválidas, preservação/reenvio da fila, migrações do banco e telas, além de contagem com tela apagada. O diagnóstico HTTPS real no Android não obteve confirmação de recebimento. Nenhum alerta real foi disparado.

## Compatibilidade 1.3.1

Restaurada a confirmação por `status: sucesso`, sem tornar ID, nome ou tipo retornados requisitos adicionais. Retomado o envio automático na primeira localização e a cada minuto. O endereço, o POST JSON e as unidades do Swagger permanecem iguais à versão 1.2. O envio manual e os ajustes visuais foram preservados. A falha observada na rede de teste não identifica, por si só, a causa de um problema no celular do usuário. O Galaxy A34 não disponibiliza sensor de temperatura ambiente; essa indicação foi esclarecida na tela, sem substituir a leitura ausente por temperatura da bateria ou previsão online.

## Versão 1.3.2

Removido o cartão Dados para a API do Início e dos Ajustes, incluindo o botão de envio manual. O nome permanece em Ajustes e o envio automático durante o monitoramento continua pela mesma integração. As confirmações e pendências continuam no Histórico. As descrições anteriores de envio manual referem-se às versões 1.3 e 1.3.1.
