package com.campus.food.config;

import com.campus.food.domain.*;
import com.campus.food.domain.enums.*;
import com.campus.food.repo.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 首次启动时初始化演示数据（用户/供应商/食材/库存/本周菜单） */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final AppUserRepo userRepo;
    private final SupplierRepo supplierRepo;
    private final IngredientRepo ingredientRepo;
    private final StockLevelRepo stockLevelRepo;
    private final WeeklyMenuRepo menuRepo;
    private final MenuMealRepo mealRepo;
    private final MenuMealIngredientRepo mealIngredientRepo;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed-demo-data:true}")
    private boolean seedDemoData;

    @Override
    @Transactional
    public void run(String... args) {
        if (!seedDemoData || userRepo.count() > 0) {
            return;
        }
        log.info("初始化演示数据...");

        // 用户
        user("inspector", "inspector123", "王验收", Role.INSPECTOR, null);
        user("buyer", "buyer123", "李采购", Role.PROCUREMENT, null);
        user("canteen", "canteen123", "赵食堂", Role.CANTEEN, null);
        user("admin", "admin123", "陈校长", Role.SCHOOL_ADMIN, null);

        // 供应商
        Supplier veg = supplier("SUP001", "绿源蔬菜合作社", "刘经理", "13800000001",
                "VEGETABLE,FRUIT", false, "HT-2026-001");
        Supplier meat = supplier("SUP002", "惠民肉联食品公司", "张经理", "13800000002",
                "MEAT", true, "HT-2026-002");
        Supplier dairy = supplier("SUP003", "康源乳业配送中心", "孙经理", "13800000003",
                "DAIRY", true, "HT-2026-003");
        Supplier grain = supplier("SUP004", "丰收粮油调味批发部", "周经理", "13800000004",
                "GRAIN_OIL,CONDIMENT", false, "HT-2026-004");

        // 供应商账号（绿源蔬菜合作社，用于提交整改材料）
        user("supplier01", "supplier123", "绿源-刘经理", Role.SUPPLIER, veg);

        // 食材
        Ingredient rice = ingredient("ING001", "大米", IngredientCategory.GRAIN_OIL, "kg", "25kg/袋",
                false, null, null, 180, "200", "6.00", null);
        Ingredient flour = ingredient("ING002", "面粉", IngredientCategory.GRAIN_OIL, "kg", "25kg/袋",
                false, null, null, 180, "100", "5.00", null);
        ingredient("ING003", "食用油", IngredientCategory.GRAIN_OIL, "L", "10L/桶",
                false, null, null, 365, "60", "12.00", null);
        Ingredient pork = ingredient("ING004", "猪肉", IngredientCategory.MEAT, "kg", "冷鲜",
                true, "-2", "4", 3, "50", "30.00", "PORK");
        Ingredient beef = ingredient("ING005", "牛肉", IngredientCategory.MEAT, "kg", "冷鲜",
                true, "-2", "4", 3, "30", "60.00", null);
        Ingredient egg = ingredient("ING006", "鸡蛋", IngredientCategory.DAIRY, "kg", "箱装",
                false, null, null, 15, "40", "10.00", null);
        Ingredient cabbage = ingredient("ING007", "大白菜", IngredientCategory.VEGETABLE, "kg", "散装",
                false, null, null, 5, "80", "3.00", "LEAFY");
        Ingredient spinach = ingredient("ING008", "菠菜", IngredientCategory.VEGETABLE, "kg", "散装",
                false, null, null, 3, "40", "5.00", "LEAFY");
        Ingredient apple = ingredient("ING009", "苹果", IngredientCategory.FRUIT, "kg", "筐装",
                false, null, null, 15, "50", "8.00", null);
        Ingredient milk = ingredient("ING010", "鲜牛奶", IngredientCategory.DAIRY, "L", "250ml/盒",
                true, "0", "6", 7, "100", "4.00", null);
        ingredient("ING011", "食盐", IngredientCategory.CONDIMENT, "kg", "500g/袋",
                false, null, null, 720, "20", "2.00", null);
        ingredient("ING012", "酱油", IngredientCategory.CONDIMENT, "L", "1.9L/桶",
                false, null, null, 540, "30", "6.00", null);

        // 初始库存（大白菜故意低于安全线，演示安全线预警）
        stock(rice, "300");
        stock(flour, "120");
        stock(pork, "20");
        stock(beef, "10");
        stock(egg, "50");
        stock(cabbage, "50");
        stock(spinach, "60");
        stock(apple, "80");
        stock(milk, "60");

        // 菜单（周一 ~ 周五）；若本周工作日已全部过去（周末启动），则种下一周的菜单
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        if (monday.plusDays(4).isBefore(LocalDate.now())) {
            monday = monday.plusWeeks(1);
        }
        WeeklyMenu menu = new WeeklyMenu();
        menu.setWeekStart(monday);
        menu.setStatus(MenuStatus.PUBLISHED);
        menu.setRemark("秋季学期第6周菜单");
        menuRepo.save(menu);

        MenuMeal m1 = meal(menu, monday, MealType.LUNCH, "白菜猪肉炖粉条", 500);
        mmi(m1, cabbage, "30");
        mmi(m1, pork, "20");

        MenuMeal m2 = meal(menu, monday.plusDays(1), MealType.LUNCH, "红烧牛肉面", 480);
        mmi(m2, beef, "25");
        mmi(m2, flour, "40");

        MenuMeal m3 = meal(menu, monday.plusDays(2), MealType.BREAKFAST, "牛奶鸡蛋早餐", 500);
        mmi(m3, milk, "120");
        mmi(m3, egg, "30");

        MenuMeal m4 = meal(menu, monday.plusDays(2), MealType.LUNCH, "菠菜炒蛋", 460);
        mmi(m4, spinach, "25");
        mmi(m4, egg, "20");

        MenuMeal m5 = meal(menu, monday.plusDays(3), MealType.LUNCH, "米饭套餐", 500);
        mmi(m5, rice, "80");
        mmi(m5, pork, "15");

        MenuMeal m6 = meal(menu, monday.plusDays(4), MealType.LUNCH, "苹果加餐", 500);
        mmi(m6, apple, "60");

        log.info("演示数据初始化完成：4 供应商 / 12 食材 / 本周菜单 {} 起", monday);
    }

    private void user(String username, String rawPassword, String fullName, Role role, Supplier linkedSupplier) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setFullName(fullName);
        u.setRole(role);
        u.setSupplier(linkedSupplier);
        userRepo.save(u);
    }

    private Supplier supplier(String code, String name, String contact, String phone,
                              String categories, boolean coldChain, String contractNo) {
        Supplier s = new Supplier();
        s.setCode(code);
        s.setName(name);
        s.setContactPerson(contact);
        s.setPhone(phone);
        s.setCategories(categories);
        s.setColdChainCapable(coldChain);
        s.setContractNo(contractNo);
        return supplierRepo.save(s);
    }

    private Ingredient ingredient(String code, String name, IngredientCategory category, String unit,
                                  String spec, boolean coldChain, String minTemp, String maxTemp,
                                  Integer shelfLifeDays, String safetyStock, String refPrice,
                                  String substituteGroup) {
        Ingredient i = new Ingredient();
        i.setCode(code);
        i.setName(name);
        i.setCategory(category);
        i.setUnit(unit);
        i.setSpec(spec);
        i.setRequiresColdChain(coldChain);
        i.setColdChainMinTemp(minTemp == null ? null : new BigDecimal(minTemp));
        i.setColdChainMaxTemp(maxTemp == null ? null : new BigDecimal(maxTemp));
        i.setShelfLifeDays(shelfLifeDays);
        i.setSafetyStock(new BigDecimal(safetyStock));
        i.setReferencePrice(new BigDecimal(refPrice));
        i.setSubstituteGroup(substituteGroup);
        return ingredientRepo.save(i);
    }

    private void stock(Ingredient ingredient, String qty) {
        StockLevel s = new StockLevel();
        s.setIngredient(ingredient);
        s.setQuantity(new BigDecimal(qty));
        s.setUpdatedAt(LocalDateTime.now());
        stockLevelRepo.save(s);
    }

    private MenuMeal meal(WeeklyMenu menu, LocalDate date, MealType type, String dish, int servings) {
        MenuMeal m = new MenuMeal();
        m.setMenu(menu);
        m.setDate(date);
        m.setMealType(type);
        m.setDishName(dish);
        m.setServings(servings);
        return mealRepo.save(m);
    }

    private void mmi(MenuMeal meal, Ingredient ingredient, String qty) {
        MenuMealIngredient mmi = new MenuMealIngredient();
        mmi.setMeal(meal);
        mmi.setIngredient(ingredient);
        mmi.setQuantity(new BigDecimal(qty));
        mealIngredientRepo.save(mmi);
    }
}
