package com.unistack.tamboo.mgt.common.page;

import org.springframework.data.domain.Page;

import java.util.ArrayList;

/**
 * @author Gyges Zean
 */
public class PageConvert {

    /**
     * JPA查询获得的JPA 对象转为 rest对外包装的pageData对象
     *
     * @param jpaPage  通过jpa查询获得的Page对象
     * @param pageSize 每页限制的条数
     * @return
     */
    @SuppressWarnings(value = "unchecked")
    public static PaginationData convertJpaPage2PaginationData(Page jpaPage, int pageSize) {

        PaginationData paginationData = new PaginationData();

        Pagination pagination = new Pagination();

        if (jpaPage.getSize() == 0) {
            pagination.setPageIndex(1);
            pagination.setPageSize(pageSize);
            pagination.setTotalPage(1);
            pagination.setResultSize(0);

            paginationData.setPagination(pagination);
            paginationData.setPageData(new ArrayList<>());
        } else {
            pagination.setPageIndex(jpaPage.getNumber() + 1);
            pagination.setPageSize(jpaPage.getSize());
            pagination.setTotalPage(jpaPage.getTotalPages());
            pagination.setResultSize(jpaPage.getTotalElements());
            paginationData.setPagination(pagination);
            paginationData.setPageData(jpaPage.getContent());
        }
        return paginationData;
    }

}
