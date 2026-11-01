#pragma once

#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <typeindex>
#include <typeinfo>
#include <utility>

namespace cero {

class Dependencias {
public:
    template <class T, class F>
    Dependencias& registrar(F fabrica) {
        recetas_.insert_or_assign(std::type_index{typeid(T)},
                                  [f = std::move(fabrica)](Dependencias& d) -> Hecho {
                                      return std::shared_ptr<T>{f(d)};
                                  });
        return *this;
    }

    template <class T>
    Dependencias& poner(std::shared_ptr<T> valor) {
        return registrar<T>([valor = std::move(valor)](Dependencias&) { return valor; });
    }

    template <class T>
    std::shared_ptr<T> obtener() {
        return std::static_pointer_cast<T>(resolver(typeid(T)));
    }

private:
    using Hecho = std::shared_ptr<void>;

    Hecho resolver(std::type_index tipo);

    std::map<std::type_index, std::function<Hecho(Dependencias&)>> recetas_;
    std::mutex candado_;
    std::map<std::type_index, Hecho> hechos_;
};

}
