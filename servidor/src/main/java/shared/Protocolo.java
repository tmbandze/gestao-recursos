package shared;

public class Protocolo {
    // Comandos cliente → servidor
    public static final String LOGIN              = "LOGIN";
    public static final String LISTAR            = "LISTAR";
    public static final String LISTAR_DISPONIVEIS = "LISTAR_DISPONIVEIS";
    public static final String PESQUISAR         = "PESQUISAR";
    public static final String INSERIR           = "INSERIR";
    public static final String REQUISITAR        = "REQUISITAR";
    public static final String DEVOLVER          = "DEVOLVER";
    public static final String DETALHES          = "DETALHES";
    public static final String HISTORICO         = "HISTORICO";
    public static final String RELATORIO         = "RELATORIO";
    public static final String SAIR              = "SAIR";

    // Respostas síncronas servidor → cliente
    public static final String OK      = "OK";
    public static final String ERRO    = "ERRO";
    public static final String LIVROS  = "LIVROS";
    public static final String LOG     = "LOG";
    public static final String STATS   = "STATS";

    // Mensagens assíncronas (push)
    public static final String NOTIFICACAO = "NOTIFICACAO";
    public static final String ATUALIZAR   = "ATUALIZAR";

    // Comandos de administração
    public static final String ADMIN_USUARIOS = "ADMIN_USUARIOS";
    public static final String ADMIN_SISTEMA  = "ADMIN_SISTEMA";
    public static final String USUARIOS       = "USUARIOS";
    public static final String SISTEMA        = "SISTEMA";
}
