package com.zml.oa.action;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;

import org.activiti.engine.ActivitiException;
import org.activiti.engine.RuntimeService;
import org.activiti.engine.TaskService;
import org.activiti.engine.runtime.ProcessInstance;
import org.activiti.engine.task.Task;
import org.apache.log4j.Logger;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.zml.oa.entity.BaseVO;
import com.zml.oa.entity.CommentVO;
import com.zml.oa.entity.ExpenseAccount;
import com.zml.oa.entity.User;
import com.zml.oa.service.IExpenseService;
import com.zml.oa.service.IProcessService;
import com.zml.oa.service.IUserService;
import com.zml.oa.util.UserUtil;

/**
 * @ClassName: VacationAction
 * @Description:报销控制类
 * @author: zml
 * @date: 2014-12-7 下午14:30:20
 *
 */
@Controller
@RequestMapping("/expenseAction")
public class ExpenseAction {
	private static final Logger logger = Logger.getLogger(ExpenseAction.class);
	@Autowired
	private IExpenseService expenseService;
	
	@Autowired
	protected RuntimeService runtimeService;
    
    @Autowired
    protected TaskService taskService;
	
	@Autowired
	private IUserService userService;
	
	@Autowired
	private IProcessService processService;
	
	
	/**
	 * 跳转添加页面
	 * @param model
	 * @return
	 */
	@RequiresPermissions("user:expense:toAdd")
	@RequestMapping(value = "/toAdd", method = RequestMethod.GET)
	public ModelAndView toAdd(Model model){
		if(!model.containsAttribute("expense")) {
            model.addAttribute("expense", new ExpenseAccount());
        }
		return new ModelAndView("expense/add_expense").addObject(model);
	}
	
	/**
	 * 详细信息
	 * @param id
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequiresPermissions("user:expense:details")
	@RequestMapping(value="/details/{id}", method = RequestMethod.GET)
	public String details(@PathVariable("id") Integer id, Model model) throws Exception{
		ExpenseAccount expense = this.expenseService.findById(id);
		model.addAttribute("expense", expense);
		return "/expense/details_expense";
	}
	
	/**
	 * 添加并启动报销流程
	 * @param expense
	 * @param results
	 * @param redirectAttributes
	 * @param session
	 * @param model
	 * @return
	 * @throws Exception
	 */
	@RequiresPermissions("user:expense:doAdd")
	@RequestMapping(value = "/doAdd", method = RequestMethod.POST)
	public String doAdd(
			@ModelAttribute("expense") @Valid ExpenseAccount expense,BindingResult results, 
			RedirectAttributes redirectAttributes, 
			HttpSession session, 
			Model model) throws Exception{
        User user = UserUtil.getUserFromSession(session);
        
        if(results.hasErrors()){
        	model.addAttribute("expense", expense);
        	return "expense/add_expense";
        }
        
        
        // 用户未登录不能操作，实际应用使用权限框架实现，例如Spring Security、Shiro等
        if (user == null || user.getId() == null) {
        	model.addAttribute("msg", "登录超时，请重新登录!");
            return "login";
        }
        
        expense.setApplyDate(new Date());
        expense.setUserId(user.getId());
        expense.setUser_name(user.getName());
        expense.setTitle(user.getName()+" 的报销申请");
        expense.setBusinessType(BaseVO.EXPENSE);
        expense.setStatus(BaseVO.PENDING);
        this.expenseService.doAdd(expense);
        String businessKey = expense.getId().toString();
        expense.setBusinessKey(businessKey);
        try{
        	String processInstanceId = this.processService.startExpense(expense);
            redirectAttributes.addFlashAttribute("message", "流程已启动，流程ID：" + processInstanceId);
            logger.info("processInstanceId: "+processInstanceId);
        }catch (ActivitiException e) {
            if (e.getMessage().indexOf("no processes deployed with key") != -1) {
                logger.warn("没有部署流程!", e);
                redirectAttributes.addFlashAttribute("error", "没有部署流程，请在[工作流]->[流程管理]页面点击<重新部署流程>");
            } else {
                logger.error("启动报销流程失败：", e);
                redirectAttributes.addFlashAttribute("error", "系统内部错误！");
            }
        } catch (Exception e) {
            logger.error("启动报销流程失败：", e);
            redirectAttributes.addFlashAttribute("error", "系统内部错误！");
        }
        return "redirect:/expenseAction/toAdd";
	}

	
	/**
     * 审批报销流程
     * @param taskId
     * @param model
     * @return
     * @throws NumberFormatException
     * @throws Exception
     */
	@RequiresPermissions("user:expense:toApproval") 	//*代表 财务， 如果业务编号，也可以代表其他角色
    @RequestMapping("/toApproval/{taskId}")
    public String toApproval(@PathVariable("taskId") String taskId, Model model) throws NumberFormatException, Exception{
    	Task task = this.taskService.createTaskQuery().taskId(taskId).singleResult();
		// 根据任务查询流程实例
    	String processInstanceId = task.getProcessInstanceId();
		ProcessInstance pi = this.runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
		ExpenseAccount expense = (ExpenseAccount) this.runtimeService.getVariable(pi.getId(), "entity");
		expense.setTask(task);
		List<CommentVO> commentList = this.processService.getComments(processInstanceId);
		model.addAttribute("commentList", commentList);
		model.addAttribute("expense", expense);
    	return "expense/audit_expense";
    }
    
    /**
     * 完成任务
     * @param content
     * @param completeFlag
     * @param taskId
     * @param redirectAttributes
     * @param session
     * @return
     * @throws Exception
     */
	@RequiresPermissions("user:expense:complate")
    @RequestMapping("/complate/{taskId}")
    public String complate(
    		@RequestParam("expenseId") Integer expenseId,
    		@PathVariable("taskId") String taskId, 
    		RedirectAttributes redirectAttributes,
    		HttpSession session) throws Exception{
    	User user = UserUtil.getUserFromSession(session);
    	
        ExpenseAccount expense = this.expenseService.findById(expenseId);
		Map<String, Object> variables = new HashMap<String, Object>();
//		variables.put("auditGroup", "finance");
		expense.setStatus(BaseVO.APPROVAL_SUCCESS);
		this.expenseService.doUpdate(expense);
		// 完成任务
		this.processService.complete(taskId, null, user.getId().toString(), variables);
		redirectAttributes.addFlashAttribute("message", "任务办理完成！");
    	return "redirect:/processAction/todoTaskList_page";
    }
}
