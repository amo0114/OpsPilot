package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWorkSource;
import java.util.List;
import org.springframework.stereotype.Repository;

/** INVESTIGATING 的 Incident 与其当前 run（含已 Stop）；DIAGNOSED、等待审批及终态不在其中（07 §51 表）。 */
@Repository
class MyBatisInvestigationWorkSource implements DispatchableWorkSource {

    private final InvestigationWorkMapper mapper;

    MyBatisInvestigationWorkSource(InvestigationWorkMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<DispatchableWork.Investigation> findDispatchable() {
        return mapper.selectInvestigatingRuns();
    }
}
