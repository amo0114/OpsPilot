package io.github.ismoyuan.opspilot.infrastructure.persistence.mybatis.investigation;

import io.github.ismoyuan.opspilot.application.dispatch.DispatchableWork;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/** 可派发调查的只读查询（07 §51）；只有 SELECT。 */
@Mapper
interface InvestigationWorkMapper {

    List<DispatchableWork.Investigation> selectInvestigatingRuns();
}
