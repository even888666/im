package cn.android666.im.server.org.xmpp;

import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.handler.IQHandler;
import org.xmpp.packet.IQ;

import java.util.function.Function;

/**
 * 把与 Openfire 无关的 IQ 处理函数（如 {@link OrgIqProcessor#process}）适配为 Openfire 的 {@link IQHandler}。
 * 处理逻辑都在处理函数中，便于单元测试；本类只负责注册到 IQRouter。
 */
public class FunctionIqHandler extends IQHandler {

    /** 处理的命名空间与元素名 */
    private final IQHandlerInfo info;

    /** 处理函数：请求 → 应答 */
    private final Function<IQ, IQ> processor;

    /**
     * 创建处理器。
     *
     * @param moduleName 模块名（Openfire 日志中显示）
     * @param element    子元素名（Openfire 按命名空间路由，同一命名空间的不同元素由处理函数自行分发）
     * @param namespace  命名空间
     * @param processor  处理函数
     */
    public FunctionIqHandler(String moduleName, String element, String namespace, Function<IQ, IQ> processor) {
        super(moduleName);
        this.info = new IQHandlerInfo(element, namespace);
        this.processor = processor;
    }

    /**
     * 处理请求。
     *
     * @param packet 请求
     * @return 应答
     */
    @Override
    public IQ handleIQ(IQ packet) {
        // 结果和错误类型的 IQ 不需要应答
        if (packet.getType() == IQ.Type.result || packet.getType() == IQ.Type.error) {
            return null;
        }
        return processor.apply(packet);
    }

    /**
     * 处理的命名空间与元素名。
     *
     * @return 处理信息
     */
    @Override
    public IQHandlerInfo getInfo() {
        return info;
    }
}
