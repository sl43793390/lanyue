package com.sl.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.charts.Chart;
import com.vaadin.flow.component.charts.model.*;
import com.vaadin.flow.component.charts.model.style.SolidColor;
import com.vaadin.flow.component.datepicker.DatePicker;
import com.vaadin.flow.component.html.Main;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;

import java.time.Clock;

@Route("chart")
@PageTitle("chart")
@Menu(order = 99, icon = "vaadin:bar-chart-h", title = "demo chart")
@PermitAll // When security is enabled, allow all authenticated users
public class DemoChartView extends Main {


    private TextField description;
    private DatePicker dueDate;
    private Button createBtn;
    private Chart ratingDistrubuteChart;

    public DemoChartView(Clock clock) {

        initChart();
        add(ratingDistrubuteChart);
//        description = new TextField();
//        description.setPlaceholder("What do you want to do?");
//        description.setAriaLabel("Task description");
//        description.setMaxLength(Task.DESCRIPTION_MAX_LENGTH);
//        description.setMinWidth("20em");
//
//        dueDate = new DatePicker();
//        dueDate.setPlaceholder("Due date");
//        dueDate.setAriaLabel("Due date");
//
//        createBtn = new Button("Create", event -> createTask());
//        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
//
//        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(clock.getZone())
//                .withLocale(getLocale());
//        var dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(getLocale());
//
//        taskGrid = new Grid<>();
//        taskGrid.setItems(query -> taskService.list(toSpringPageRequest(query)).stream());
//        taskGrid.addColumn(Task::getDescription).setHeader("Description");
//        taskGrid.addColumn(task -> Optional.ofNullable(task.getDueDate()).map(dateFormatter::format).orElse("Never"))
//                .setHeader("Due Date");
//        taskGrid.addColumn(task -> dateTimeFormatter.format(task.getCreationDate())).setHeader("Creation Date");
//        taskGrid.setSizeFull();
//
//        setSizeFull();
//        addClassNames(LumoUtility.BoxSizing.BORDER, LumoUtility.Display.FLEX, LumoUtility.FlexDirection.COLUMN,
//                LumoUtility.Padding.MEDIUM, LumoUtility.Gap.SMALL);
//
//        add(new ViewToolbar("Task List", ViewToolbar.group(description, dueDate, createBtn)));
//        add(taskGrid);
    }


    private void initChart() {
        ratingDistrubuteChart = new Chart(ChartType.COLUMN);
        ratingDistrubuteChart.setWidth("50%");
        ratingDistrubuteChart.setHeight("550px");
        Configuration conf = ratingDistrubuteChart.getConfiguration();

        XAxis x = new XAxis();
        x.setCategories("A","B","C","D","E");
        conf.addxAxis(x);
        conf.setTitle("");

        YAxis y1 = new YAxis();
        y1.setTitle(new AxisTitle("样本比例 (%)"));
//		y1.setTickPositions(new Number[] {12,15,37,32,13});
        conf.addyAxis(y1);

        YAxis y2 = new YAxis();
//		y2.setTickPositions(new Number[] {25,35,37,23,26});
        y2.setTitle("累计比例(%)");
        y2.setOpposite(true);
        conf.addyAxis(y2);

        conf.getTooltip()
                .setFormatter(
                        "'<b>'+ this.series.name+'</b><br/>'+this.x +': '+ Highcharts.numberFormat(this.y,1) +'%'");
        Legend legend = new Legend();
        legend.setBackgroundColor(new SolidColor("#f3fafd"));
        conf.setLegend(legend);

        PlotOptionsColumn p1 = new PlotOptionsColumn();
        PlotOptionsColumn p2 = new PlotOptionsColumn();
        ListSeries rquestPctList = new ListSeries("test(左轴)",new Number[] {12,15,37,32,13});
        ListSeries externalPctList = new ListSeries("test2(左轴)",new Number[] {25,35,37,23,26});
        rquestPctList.setPlotOptions(p1);
        externalPctList.setPlotOptions(p2);
        conf.addSeries(rquestPctList);
        conf.addSeries(externalPctList);

        PlotOptionsSpline p3 = new PlotOptionsSpline();
        PlotOptionsSpline p4 = new PlotOptionsSpline();
        ListSeries rquestSumPctList = new ListSeries("test3(右轴)",new Number[] {11,22,37,35,23});
        ListSeries externalSumPctList = new ListSeries("test4(右轴)",new Number[] {15,25,31,13,29});
        rquestSumPctList.setPlotOptions(p3);
        rquestSumPctList.setyAxis(1);
        externalSumPctList.setPlotOptions(p4);
        externalSumPctList.setyAxis(1);
        conf.addSeries(rquestSumPctList);
        conf.addSeries(externalSumPctList);

        ratingDistrubuteChart.drawChart();
    }

}
